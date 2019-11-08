package com.riuscada.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.time.Instant
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for MeasureState.
 */
object MeasureSchema

/**
 * An MessageState schema.
 */
object MeasureSchemaV1 : MappedSchema(
        schemaFamily = MeasureSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentMeasure::class.java)) {

    @Entity
    @Table(name = "measure_states")
    class PersistentMeasure(
            @Column(name = "firstNode")
            var firstNode: String,

            @Column(name = "secondNode")
            var secondNode: String,

            @Column(name = "hostname")
            var hostname: String,

            @Column(name = "time")
            var time: Instant,

            @Column(name = "xmlData", length = 65535)
            var xmlData: String,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this(firstNode = "", secondNode = "", hostname = "", time = Instant.now(), xmlData = "", linearId = UUID.randomUUID())
    }
}