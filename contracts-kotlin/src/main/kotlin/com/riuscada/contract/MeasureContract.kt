package com.riuscada.contract

import com.riuscada.state.MeasureState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey
import java.time.Instant

class MeasureContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.riuscada.contract.MeasureContract"
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
            val measure = tx.outputsOfType<MeasureState>().single()
            "All of the participants must be signers." using (signers.containsAll(measure.participants.map { it.owningKey }))

            // Measure-specific constraints.
            "firstNode and secondNode cannot be the same entity." using (measure.firstNode != measure.secondNode)
            "time cannot be in the future." using (measure.time < Instant.now())
            "xmlData must be empty." using (measure.xmlData.isNullOrEmpty())
            "uuid must be empty" using (measure.uuid.isNullOrEmpty())
            "hostname cannot be empty" using (measure.hostname.isNotEmpty())
            "macAddress cannot be empty" using (measure.macAddress.isNotEmpty())
        }
    }

    private fun verifyUpdate(tx: LedgerTransaction, signers: Set<PublicKey>){
        val commands = tx.commands.requireSingleCommand<Commands.Update>()
        requireThat {
            // Generic constraints around the old Measure transaction.
            "there must be only one measure input." using (tx.inputs.size == 1)
            val oldMeasureState = tx.inputsOfType<MeasureState>().single()

            // Generic constraints around the generic update transaction.
            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newMeasureState = tx.outputsOfType<MeasureState>().single()
            "All of the participants must be signers." using (signers.containsAll(newMeasureState.participants.map { it.owningKey }))

            // Generic constraints around the new Measure transaction
            "firstNode and secondNode cannot be the same entity" using (newMeasureState.firstNode != newMeasureState.secondNode)
            "old hostname must be the same of the new hostname" using (oldMeasureState.hostname == newMeasureState.hostname)
            "old macAddress must be the same of the new macAddress" using (oldMeasureState.macAddress == newMeasureState.macAddress)
            "old xmlData cannot be the same of the new xmlData" using (oldMeasureState.xmlData != newMeasureState.xmlData)
            "new uuid cannot be empty" using (newMeasureState.uuid.isNotEmpty())
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