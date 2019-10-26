package com.riuscada.server.pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class IssueMeasurePojo(
        val hostname: String = "",
        val time: String = "",
        val xmlData: String = ""
)