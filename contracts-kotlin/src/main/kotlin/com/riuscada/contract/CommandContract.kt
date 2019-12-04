package com.riuscada.contract

import com.riuscada.state.CommandState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey
import java.time.Instant

class CommandContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.riuscada.contract.CommandContract"
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
            val commandState = tx.outputsOfType<CommandState>().single()
            "All of the participants must be signers." using (signers.containsAll(commandState.participants.map { it.owningKey }))

            // Command-specific constraints.
            "firstNode and secondNode cannot be the same entity." using (commandState.firstNode != commandState.secondNode)
            "time cannot be in the future." using (commandState.time < Instant.now())
            "status cannot be empty." using (commandState.status.isNotEmpty())
            "macAddress cannot be empty." using (commandState.macAddress.isNotEmpty())
            "xmlConfigData cannot be empty." using (commandState.xmlCommandData.isNotEmpty())
            "hostname cannot be empty" using (commandState.hostname.isNotEmpty())
        }
    }

    private fun verifyUpdate(tx: LedgerTransaction, signers: Set<PublicKey>){
        val commands = tx.commands.requireSingleCommand<Commands.Update>()
        requireThat {
            // Generic constraints around the old Command transaction.
            "there must be only one command input." using (tx.inputs.size == 1)
            val oldCommandState = tx.inputsOfType<CommandState>().single()

            // Generic constraints around the generic update transaction.
            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newCommandState = tx.outputsOfType<CommandState>().single()
            "All of the participants must be signers." using (signers.containsAll(newCommandState.participants.map { it.owningKey }))

            // Generic constraints around the new Command transaction
            "firstNode and secondNode cannot be the same entity" using (newCommandState.firstNode != newCommandState.secondNode)
            "old hostname must be the same of the new hostname" using (oldCommandState.hostname == newCommandState.hostname)
            "old macAddress must be the same of the new macAddress" using (oldCommandState.macAddress == newCommandState.macAddress)
            "old status cannot be the same of the new status" using (oldCommandState.status != newCommandState.status)
            "old xmlConfigData cannot be the same of the new xmlConfigData" using (oldCommandState.xmlCommandData != newCommandState.xmlCommandData)
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