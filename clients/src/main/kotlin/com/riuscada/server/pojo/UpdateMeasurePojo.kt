package com.riuscada.server.pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class UpdateMeasurePojo(
        // val measureLinearId: String = "",
        val hostname: String = "",
        val macAddress: String = "",
        val time: String = "",
        val xmlData: String = "",
        val uuid: String = ""
)