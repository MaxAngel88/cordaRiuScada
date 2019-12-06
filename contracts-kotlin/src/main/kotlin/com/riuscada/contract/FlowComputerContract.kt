package com.riuscada.contract

import com.riuscada.state.FlowComputerState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

class FlowComputerContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.riuscada.contract.FlowComputerContract"
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
            val flowComputer = tx.outputsOfType<FlowComputerState>().single()
            "All of the participants must be signers." using (signers.containsAll(flowComputer.participants.map { it.owningKey }))

            // FlowComputer-specific constraints.
            "firstNode and secondNode cannot be the same entity." using (flowComputer.firstNode != flowComputer.secondNode)
            "binaryData cannot be empty." using (flowComputer.binaryData.isNotEmpty())
            "hostname cannot be empty" using (flowComputer.hostname.isNotEmpty())
            "macAddress cannot be empty" using (flowComputer.macAddress.isNotEmpty())
        }
    }

    private fun verifyUpdate(tx: LedgerTransaction, signers: Set<PublicKey>){
        val commands = tx.commands.requireSingleCommand<Commands.Update>()
        requireThat {
            // Generic constraints around the old flowComputer transaction.
            "there must be only one flowComputer input." using (tx.inputs.size == 1)
            val oldFlowComputerState = tx.inputsOfType<FlowComputerState>().single()

            // Generic constraints around the generic update transaction.
            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newFlowComputerState = tx.outputsOfType<FlowComputerState>().single()
            "All of the participants must be signers." using (signers.containsAll(newFlowComputerState.participants.map { it.owningKey }))

            // Generic constraints around the new FlowComputer transaction
            "firstNode and secondNode cannot be the same entity" using (newFlowComputerState.firstNode != newFlowComputerState.secondNode)
            "old hostname must be the same of the new hostname" using (oldFlowComputerState.hostname == newFlowComputerState.hostname)
            "old macAddress must be the same of the new macAddress" using (oldFlowComputerState.macAddress == newFlowComputerState.macAddress)
            "old binaryData cannot be the same of the new binaryData" using (oldFlowComputerState.binaryData != newFlowComputerState.binaryData)
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