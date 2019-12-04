package com.riuscada.flow

import co.paralleluniverse.fibers.Suspendable
import com.riuscada.contract.ForcedMeasureContract
import com.riuscada.state.ForcedMeasureState
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

object ForcedMeasureFlow {

    /**
     *
     * Issue ForcedMeasure Flow ------------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class ForcedIssuer(val hostname: String,
                       val macAddress: String,
                       val xmlData: String,
                       val startTime: Instant,
                       val endTime: Instant) : FlowLogic<ForcedMeasureState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new Forced Measure.")
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
        override fun call(): ForcedMeasureState {
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
            val forcedMeasureState = ForcedMeasureState(
                    myLegalIdentity,
                    secondParty,
                    hostname,
                    macAddress,
                    Instant.now(),
                    xmlData,
                    startTime,
                    endTime,
                    UniqueIdentifier(id = UUID.randomUUID()))



            val txCommand = Command(ForcedMeasureContract.Commands.Issue(), forcedMeasureState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(forcedMeasureState, ForcedMeasureContract.ID)
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


            return forcedMeasureState
        }
    }

    @InitiatedBy(ForcedIssuer::class)
    class ForcedReceiver(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an forced measure transaction." using (output is ForcedMeasureState)
                    val forcedMeasure = output as ForcedMeasureState
                    /* "other rule forced measure" using (forced measure is new rule) */
                    "forced measure hostname cannot be empty" using (forcedMeasure.hostname.isNotEmpty())
                    "forced measure macAddress cannot be empty" using (forcedMeasure.macAddress.isNotEmpty())
                    "forced measure xmlData cannot be empty on creation" using (forcedMeasure.xmlData.isNotEmpty())
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }

    /***
     *
     * Update ForcedMeasure Flow -----------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class ForcedUpdater(val forcedMeasureLinearId: String,
                        val hostname: String,
                        val macAddress: String,
                        val xmlData: String,
                        val startTime: Instant,
                        val endTime: Instant) : FlowLogic<ForcedMeasureState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on Forced Measure.")
            object VERIFYIGN_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the otherparty signature.") {
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
        override fun call(): ForcedMeasureState {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val myLegalIdentity : Party = serviceHub.myInfo.legalIdentities.first()
            var firstPartyOrg : String = myLegalIdentity.name.organisation
            var secondParty : Party? = null

            if (firstPartyOrg != "NodeA" && firstPartyOrg != "NodeB" ) {
                throw FlowException("node " + serviceHub.myInfo.legalIdentities.first() + " cannot start the forced measure update flow")
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
            var customCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(forcedMeasureLinearId)))
            criteria = criteria.and(customCriteria)

            val oldForcedMeasureStateList = serviceHub.vaultService.queryBy<ForcedMeasureState>(
                    criteria,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (oldForcedMeasureStateList.size > 1 || oldForcedMeasureStateList.isEmpty()) throw FlowException("No forced measure state with UUID: " + UUID.fromString(forcedMeasureLinearId) + " found.")

            val oldForcedMeasureStateRef = oldForcedMeasureStateList[0]
            val oldForcedMeasureState = oldForcedMeasureStateRef.state.data

            val newForcedMeasureState = ForcedMeasureState(
                    myLegalIdentity,
                    secondParty,
                    hostname,
                    macAddress,
                    Instant.now(),
                    xmlData,
                    startTime,
                    endTime,
                    UniqueIdentifier(id = UUID.randomUUID())
            )

            val txCommand = Command(ForcedMeasureContract.Commands.Update(), newForcedMeasureState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(oldForcedMeasureStateRef)
                    .addOutputState(newForcedMeasureState, ForcedMeasureContract.ID)
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

                oldForcedMeasureState.firstNode -> {
                    otherFlow = initiateFlow(oldForcedMeasureState.secondNode)
                }

                oldForcedMeasureState.secondNode -> {
                    otherFlow = initiateFlow(oldForcedMeasureState.firstNode)
                }

                else -> throw FlowException("node "+serviceHub.myInfo.legalIdentities.first()+" cannot start the flow")
            }

            // Send the state to the counterparty, and receive it back with their signature.
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx, setOf(otherFlow), FINALISING_TRANSACTION.childProgressTracker()))
            return newForcedMeasureState
        }

        @InitiatedBy(ForcedUpdater::class)
        class ForcedUpdateAcceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
            @Suspendable
            override fun call(): SignedTransaction {
                val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        val output = stx.tx.outputs.single().data
                        "This must be an forced measure transaction." using (output is ForcedMeasureState)
                        val forcedMeasure = output as ForcedMeasureState
                        /* "other rule measure" using (output is new rule) */
                        "measure hostname cannot be empty" using (forcedMeasure.hostname.isNotEmpty())
                        "measure macAddress cannot be empty" using (forcedMeasure.macAddress.isNotEmpty())
                        "measure xmlData cannot be empty on creation" using (forcedMeasure.xmlData.isNotEmpty())
                    }
                }
                val txId = subFlow(signTransactionFlow).id

                return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))

            }
        }
    }
}