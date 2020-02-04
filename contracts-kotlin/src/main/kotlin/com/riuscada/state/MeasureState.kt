package com.riuscada.state

import com.riuscada.contract.MeasureContract
import com.riuscada.schema.MeasureSchemaV1
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import java.time.Instant

@BelongsToContract(MeasureContract::class)
data class MeasureState(val firstNode: Party,
                        val secondNode: Party,
                        val hostname: String,
                        val macAddress: String,
                        val time: Instant,
                        val xmlData: String,
                        val uuid: String,
                        override val linearId: UniqueIdentifier = UniqueIdentifier()):
        LinearState, QueryableState {

    /** The public keys of the involved parties. */
    override val participants: List<AbstractParty> get() = listOf(firstNode, secondNode)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is MeasureSchemaV1 -> MeasureSchemaV1.PersistentMeasure(
                    this.firstNode.name.toString(),
                    this.secondNode.name.toString(),
                    this.hostname,
                    this.macAddress,
                    this.time,
                    this.xmlData,
                    this.uuid,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(MeasureSchemaV1)
}