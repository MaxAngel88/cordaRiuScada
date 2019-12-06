package com.riuscada.flow

import co.paralleluniverse.fibers.Suspendable
import com.riuscada.contract.FlowComputerContract
import com.riuscada.state.FlowComputerState
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import java.time.Instant
import java.util.*

object FlowComputerFlow {

    /**
     *
     * Issue FlowComputer Flow ------------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class FlowComputerIssuer(val hostname: String,
                             val macAddress: String,
                             val binaryData: String) : FlowLogic<FlowComputerState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new FlowComputer.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the otherNode signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): FlowComputerState {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION

            val myLegalIdentity: Party = serviceHub.myInfo.legalIdentities.first()
            var firstPartyOrg : String = myLegalIdentity.name.organisation
            var secondParty : Party? = null

            when (firstPartyOrg) {
                "NodeA" -> {
                    //var secondX500Name = CordaX500Name.parse("O=NodeB,L=Milan,C=IT")
                    //val party = rpcOps.wellKnownPartyFromX500Name(x500Name)
                    //secondParty = serviceHub.myInfo.identityFromX500Name(secondX500Name)
                    var secondX500Name = CordaX500Name(organisation = "NodeB", locality = "Milan", country = "IT")
                    secondParty = serviceHub.networkMapCache.getPeerByLegalName(secondX500Name)!!
                }
                "NodeB" -> {
                    //var secondX500Name = CordaX500Name.parse("O=NodeA,L=Milan,C=IT")
                    //val party = rpcOps.wellKnownPartyFromX500Name(x500Name)
                    //secondParty = serviceHub.myInfo.identityFromX500Name(secondX500Name)
                    var secondX500Name = CordaX500Name(organisation = "NodeA", locality = "Milan", country = "IT")
                    secondParty = serviceHub.networkMapCache.getPeerByLegalName(secondX500Name)!!
                }
                else ->  throw FlowException("LegalIdentities not in network: myLegalName = $myLegalIdentity secondName = $secondParty")
            }

            // Generate an unsigned transaction.
            val flowComputerState = FlowComputerState(
                    myLegalIdentity,
                    secondParty,
                    hostname,
                    macAddress,
                    Instant.now(),
                    binaryData,
                    UniqueIdentifier(id = UUID.randomUUID()))


            val txCommand = Command(FlowComputerContract.Commands.Issue(), flowComputerState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(flowComputerState, FlowComputerContract.ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the other node, and receive it back with their signature.
            val secondPartySession = initiateFlow(secondParty)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(secondPartySession), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx, setOf(secondPartySession), FINALISING_TRANSACTION.childProgressTracker()))


            return flowComputerState
        }
    }

    @InitiatedBy(FlowComputerIssuer::class)
    class FlowComputerReceiver(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an flowComputer transaction." using (output is FlowComputerState)
                    val flowComputerState = output as FlowComputerState
                    /* "other rule flowComputer" using (flowComputer is new rule) */
                    "flowComputer hostname cannot be empty" using (flowComputerState.hostname.isNotEmpty())
                    "flowComputer macAddress cannot be empty" using (flowComputerState.macAddress.isNotEmpty())
                    "flowComputer binaryData cannot be empty on creation" using (flowComputerState.binaryData.isNotEmpty())
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }

    /***
     *
     * Update FlowComputer Flow -----------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class FlowComputerUpdater(val flowComputerLinearId: String,
                              val hostname: String,
                              val macAddress: String,
                              val binaryData: String) : FlowLogic<FlowComputerState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on update FlowComputer.")
            object VERIFYIGN_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the destinatario's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYIGN_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): FlowComputerState {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val myLegalIdentity : Party = serviceHub.myInfo.legalIdentities.first()
            var firstPartyOrg : String = myLegalIdentity.name.organisation
            var secondParty : Party? = null

            if (firstPartyOrg != "NodeA" && firstPartyOrg != "NodeB" ) {
                throw FlowException("node " + serviceHub.myInfo.legalIdentities.first() + " cannot start the update flow")
            }

            when (firstPartyOrg) {
                "NodeA" -> {
                    //var secondX500Name = CordaX500Name.parse("O=NodeB,L=Milan,C=IT")
                    //val party = rpcOps.wellKnownPartyFromX500Name(x500Name)
                    //secondParty = serviceHub.myInfo.identityFromX500Name(secondX500Name)
                    var secondX500Name = CordaX500Name(organisation = "NodeB", locality = "Milan", country = "IT")
                    secondParty = serviceHub.networkMapCache.getPeerByLegalName(secondX500Name)!!
                }
                "NodeB" -> {
                    //var secondX500Name = CordaX500Name.parse("O=NodeA,L=Milan,C=IT")
                    //val party = rpcOps.wellKnownPartyFromX500Name(x500Name)
                    //secondParty = serviceHub.myInfo.identityFromX500Name(secondX500Name)
                    var secondX500Name = CordaX500Name(organisation = "NodeA", locality = "Milan", country = "IT")
                    secondParty = serviceHub.networkMapCache.getPeerByLegalName(secondX500Name)!!
                }
                else ->  throw FlowException("LegalIdentities not in network: myLegalName = $myLegalIdentity secondName = $secondParty")
            }

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            var criteria: QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            var customCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(flowComputerLinearId)))
            criteria = criteria.and(customCriteria)

            val oldFlowComputerStateList = serviceHub.vaultService.queryBy<FlowComputerState>(
                    criteria,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (oldFlowComputerStateList.size > 1 || oldFlowComputerStateList.isEmpty()) throw FlowException("No flowComputer state with UUID: " + UUID.fromString(flowComputerLinearId) + " found.")

            val oldFlowComputerStateRef = oldFlowComputerStateList[0]
            val oldFlowComputerState = oldFlowComputerStateRef.state.data

            val newFlowComputerState = FlowComputerState(
                    myLegalIdentity,
                    secondParty,
                    hostname,
                    macAddress,
                    Instant.now(),
                    binaryData,
                    UniqueIdentifier(id = UUID.randomUUID())
            )

            val txCommand = Command(FlowComputerContract.Commands.Update(), newFlowComputerState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(oldFlowComputerStateRef)
                    .addOutputState(newFlowComputerState, FlowComputerContract.ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYIGN_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS

            var otherFlow : FlowSession? = null

            // Send the state to the counterparty, and receive it back with their signature.
            when(myLegalIdentity){

                oldFlowComputerState.firstNode -> {
                    otherFlow = initiateFlow(oldFlowComputerState.secondNode)
                }

                oldFlowComputerState.secondNode -> {
                    otherFlow = initiateFlow(oldFlowComputerState.firstNode)
                }

                else -> throw FlowException("node "+serviceHub.myInfo.legalIdentities.first()+" cannot start the flow")
            }

            // Send the state to the counterparty, and receive it back with their signature.
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx, setOf(otherFlow), FINALISING_TRANSACTION.childProgressTracker()))
            return newFlowComputerState
        }

        @InitiatedBy(FlowComputerUpdater::class)
        class FlowComputerUpdateAcceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
            @Suspendable
            override fun call(): SignedTransaction {
                val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        val output = stx.tx.outputs.single().data
                        "This must be an flowComputer transaction." using (output is FlowComputerState)
                        val flowComputer = output as FlowComputerState
                        /* "other rule flowComputer" using (output is new rule) */
                        "flowComputer hostname cannot be empty" using (flowComputer.hostname.isNotEmpty())
                        "flowComputer macAddress cannot be empty" using (flowComputer.macAddress.isNotEmpty())
                        "flowComputer binaryData cannot be empty on creation" using (flowComputer.binaryData.isNotEmpty())
                    }
                }
                val txId = subFlow(signTransactionFlow).id

                return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))

            }
        }
    }
}