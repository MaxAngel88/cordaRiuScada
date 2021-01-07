package com.riuscada.server.pojo

import net.corda.core.serialization.CordaSerializable
import java.time.Instant

@CordaSerializable
data class DateRequestPojo(
        val riu_target: String = "",
        val startTime: Instant = Instant.now().minusSeconds(86400 * 2),
        val endTime: Instant = Instant.now()
)