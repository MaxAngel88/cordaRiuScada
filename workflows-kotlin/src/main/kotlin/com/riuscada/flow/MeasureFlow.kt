package com.riuscada.flow

import co.paralleluniverse.fibers.Suspendable
import com.riuscada.contract.MeasureContract
import com.riuscada.state.MeasureState
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

object MeasureFlow {

    /**
     *
     * Issue Measure Flow ------------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class Issuer(val hostname: String,
                 val time: Instant,
                 val xmlData: String) : FlowLogic<MeasureState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new Measure.")
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
        override fun call(): MeasureState {
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
            val measureState = MeasureState(
                    myLegalIdentity,
                    secondParty,
                    hostname,
                    time,
                    xmlData,
                    UniqueIdentifier(id = UUID.randomUUID()))

            val txCommand = Command(MeasureContract.Commands.Issue(), measureState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(measureState, MeasureContract.ID)
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
            return measureState
        }
    }

    @InitiatedBy(Issuer::class)
    class Receiver(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an measure transaction." using (output is MeasureState)
                    val measure = output as MeasureState
                    /* "other rule measure" using (measure is new rule) */
                    "measure hostname cannot be empty" using (measure.hostname.isNotEmpty())
                    "measure xmlData must be empty on creation" using (measure.xmlData.isEmpty())
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }

    /***
     *
     * Update Measure Flow -----------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class Updater(val measureLinearId: String,
                  val hostname: String,
                  val time: Instant,
                  val xmlData: String) : FlowLogic<MeasureState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on transfert Message.")
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
        override fun call(): MeasureState {
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
            var customCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(measureLinearId)))
            criteria = criteria.and(customCriteria)

            val oldMeasureStateList = serviceHub.vaultService.queryBy<MeasureState>(
                    criteria,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (oldMeasureStateList.size > 1 || oldMeasureStateList.isEmpty()) throw FlowException("No measure state with UUID: " + UUID.fromString(measureLinearId) + " found.")

            val oldMeasureStateRef = oldMeasureStateList[0]
            val oldMeasureState = oldMeasureStateRef.state.data

            val newMeasureState = MeasureState(
                    myLegalIdentity,
                    secondParty,
                    hostname,
                    time,
                    xmlData,
                    UniqueIdentifier(id = UUID.randomUUID())
            )

            val txCommand = Command(MeasureContract.Commands.Update(), newMeasureState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(oldMeasureStateRef)
                    .addOutputState(newMeasureState, MeasureContract.ID)
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

                oldMeasureState.firstNode -> {
                    otherFlow = initiateFlow(oldMeasureState.secondNode)
                }

                oldMeasureState.secondNode -> {
                    otherFlow = initiateFlow(oldMeasureState.firstNode)
                }

                else -> throw FlowException("node "+serviceHub.myInfo.legalIdentities.first()+" cannot start the flow")
            }

            // Send the state to the counterparty, and receive it back with their signature.
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx, setOf(otherFlow), FINALISING_TRANSACTION.childProgressTracker()))
            return newMeasureState
        }

        @InitiatedBy(Updater::class)
        class UpdateAcceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
            @Suspendable
            override fun call(): SignedTransaction {
                val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        val output = stx.tx.outputs.single().data
                        "This must be an measure transaction." using (output is MeasureState)
                        val measure = output as MeasureState
                        /* "other rule measure" using (output is new rule) */
                        "measure hostname cannot be empty" using (measure.hostname.isNotEmpty())
                        "measure xmlData cannot be empty on creation" using (measure.xmlData.isNotEmpty())
                    }
                }
                val txId = subFlow(signTransactionFlow).id

                return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))

            }
        }
    }
}