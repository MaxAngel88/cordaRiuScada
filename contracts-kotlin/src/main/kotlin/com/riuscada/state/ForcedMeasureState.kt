package com.riuscada.state

import com.riuscada.contract.ForcedMeasureContract
import com.riuscada.schema.ForcedMeasureSchemaV1
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import java.time.Instant

@BelongsToContract(ForcedMeasureContract::class)
data class ForcedMeasureState(val firstNode: Party,
                              val secondNode: Party,
                              val hostname: String,
                              val macAddress: String,
                              val requestTime: Instant,
                              val xmlData: String,
                              val startTime: Instant,
                              val endTime: Instant,
                              override val linearId: UniqueIdentifier = UniqueIdentifier()):
        LinearState, QueryableState {

    /** The public keys of the involved parties. */
    override val participants: List<AbstractParty> get() = listOf(firstNode, secondNode)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is ForcedMeasureSchemaV1 -> ForcedMeasureSchemaV1.PersistentForcedMeasure(
                    this.firstNode.name.toString(),
                    this.secondNode.name.toString(),
                    this.hostname,
                    this.macAddress,
                    this.requestTime,
                    this.xmlData,
                    this.startTime,
                    this.endTime,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(ForcedMeasureSchemaV1)
}