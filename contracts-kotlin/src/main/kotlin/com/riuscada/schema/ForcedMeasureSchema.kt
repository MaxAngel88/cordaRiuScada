package com.riuscada.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.time.Instant
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for ForcedMeasureState.
 */
object ForcedMeasureSchema

/**
 * An ForcedMeasureState schema.
 */
object ForcedMeasureSchemaV1 : MappedSchema(
        schemaFamily = ForcedMeasureSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentForcedMeasure::class.java)) {

    @Entity
    @Table(name = "forced_measure_states")
    class PersistentForcedMeasure(
            @Column(name = "firstNode")
            var firstNode: String,

            @Column(name = "secondNode")
            var secondNode: String,

            @Column(name = "hostname")
            var hostname: String,

            @Column(name = "macAddress")
            var macAddress: String,

            @Column(name = "request_time")
            var requestTime: Instant,

            @Column(name = "xmlData", length = 65535)
            var xmlData: String,

            @Column(name = "start_time")
            var startTime: Instant,

            @Column(name = "end_time")
            var endTime: Instant,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this(firstNode = "", secondNode = "", hostname = "", macAddress = "", requestTime = Instant.now(), xmlData = "", startTime = Instant.now(), endTime = Instant.now(), linearId = UUID.randomUUID())
    }
}