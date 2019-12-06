package com.riuscada.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.time.Instant
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Lob
import javax.persistence.Table

/**
 * The family of schemas for FlowComputerState.
 */
object FlowComputerSchema

/**
 * An FlowComputerState schema.
 */
object FlowComputerSchemaV1 : MappedSchema(
        schemaFamily = FlowComputerSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentFlowComputer::class.java)) {

    @Entity
    @Table(name = "flow_computer_states")
    class PersistentFlowComputer(
            @Column(name = "firstNode")
            var firstNode: String,

            @Column(name = "secondNode")
            var secondNode: String,

            @Column(name = "hostname")
            var hostname: String,

            @Column(name = "macAddress")
            var macAddress: String,

            @Column(name = "time")
            var time: Instant,

            @Column(name = "binaryData")
            @Lob
            var binaryData: String,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this(firstNode = "", secondNode = "", hostname = "", macAddress = "", time = Instant.now(), binaryData = "", linearId = UUID.randomUUID())
    }
}