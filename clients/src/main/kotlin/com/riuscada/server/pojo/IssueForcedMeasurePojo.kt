package com.riuscada.server.pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class IssueFocedMeasurePojo(
        val hostname: String = "",
        val macAddress: String = "",
        val xmlData: String = "",
        val startTime: String = "",
        val endTime: String = ""
)