package com.riuscada.contract

import com.riuscada.state.ForcedMeasureState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey
import java.time.Instant

class ForcedMeasureContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.riuscada.contract.ForcedMeasureContract"
    }

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val commands = tx.commandsOfType<Commands>()
        for(command in commands){
            val setOfSigners = command.signers.toSet()
            when(command.value){
                is Commands.Issue -> verifyIssue(tx, setOfSigners)
                is Commands.Update -> verifyUpdate(tx, setOfSigners)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun verifyIssue(tx: LedgerTransaction, signers: Set<PublicKey>) {
        val command = tx.commands.requireSingleCommand<Commands.Issue>()
        requireThat {
            // Generic constraints around the generic create transaction.
            "No inputs should be consumed" using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val forcedMeasure = tx.outputsOfType<ForcedMeasureState>().single()
            "All of the participants must be signers." using (signers.containsAll(forcedMeasure.participants.map { it.owningKey }))

            // ForcedMeasure-specific constraints.
            "firstNode and secondNode cannot be the same entity." using (forcedMeasure.firstNode != forcedMeasure.secondNode)
            "requestTime cannot be in the future." using (forcedMeasure.requestTime < Instant.now())
            "startTime cannot be empty" using (forcedMeasure.startTime.toString().isNotEmpty())
            "endTime cannot be empty" using (forcedMeasure.endTime.toString().isNotEmpty())
            "startTime cannot be after endTime" using (forcedMeasure.startTime.isBefore(forcedMeasure.endTime))
            "xmlData cannot be empty." using (forcedMeasure.xmlData.isNotEmpty())
            "hostname cannot be empty" using (forcedMeasure.hostname.isNotEmpty())
            "macAddress cannot be empty" using (forcedMeasure.macAddress.isNotEmpty())
        }
    }

    private fun verifyUpdate(tx: LedgerTransaction, signers: Set<PublicKey>){
        val commands = tx.commands.requireSingleCommand<Commands.Update>()
        requireThat {
            // Generic constraints around the old ForcedMeasure transaction.
            "there must be only one forcedMeasure input." using (tx.inputs.size == 1)
            val oldForcedMeasureState = tx.inputsOfType<ForcedMeasureState>().single()

            // Generic constraints around the generic update transaction.
            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newForcedMeasureState = tx.outputsOfType<ForcedMeasureState>().single()
            "All of the participants must be signers." using (signers.containsAll(newForcedMeasureState.participants.map { it.owningKey }))

            // Generic constraints around the new ForcedMeasure transaction
            "firstNode and secondNode cannot be the same entity" using (newForcedMeasureState.firstNode != newForcedMeasureState.secondNode)
            "old hostname must be the same of the new hostname" using (oldForcedMeasureState.hostname == newForcedMeasureState.hostname)
            "old macAddress must be the same of the new macAddress" using (oldForcedMeasureState.macAddress == newForcedMeasureState.macAddress)
            "requestTime cannot be in the future." using (newForcedMeasureState.requestTime < Instant.now())
            "xmlData cannot be empty." using (newForcedMeasureState.xmlData.isNotEmpty())
            "old xmlData cannot be the same of the new xmlData" using (oldForcedMeasureState.xmlData != newForcedMeasureState.xmlData)
            "startTime cannot be empty" using (newForcedMeasureState.startTime.toString().isNotEmpty())
            "endTime cannot be empty" using (newForcedMeasureState.endTime.toString().isNotEmpty())
            "startTime cannot be after endTime" using (newForcedMeasureState.startTime.isBefore(newForcedMeasureState.endTime))
        }
    }


    /**
     * This contract only implements two commands: Issue and Update.
     */
    interface Commands : CommandData {
        class Issue : Commands, TypeOnlyCommandData()
        class Update: Commands, TypeOnlyCommandData()
    }
}