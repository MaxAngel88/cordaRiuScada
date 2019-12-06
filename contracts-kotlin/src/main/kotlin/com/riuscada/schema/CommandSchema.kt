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
 * The family of schemas for CommandState.
 */
object CommandSchema

/**
 * A CommandState schema.
 */
object CommandSchemaV1 : MappedSchema(
        schemaFamily = CommandSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentCommand::class.java)) {

    @Entity
    @Table(name = "command_states")
    class PersistentCommand(
            @Column(name = "firstNode")
            var firstNode: String,

            @Column(name = "secondNode")
            var secondNode: String,

            @Column(name = "hostname")
            var hostname: String,

            @Column(name = "mac_address")
            var macAddress: String,

            @Column(name = "time")
            var time: Instant,

            @Column(name = "xmlCommandData")
            @Lob
            var xmlCommandData: String,

            @Column(name = "status")
            var status: String,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this(firstNode = "", secondNode = "", hostname = "", macAddress =  "", time = Instant.now(), xmlCommandData = "", status = "", linearId = UUID.randomUUID())
    }
}