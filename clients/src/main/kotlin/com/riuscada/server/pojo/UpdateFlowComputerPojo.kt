package com.riuscada.server.pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class UpdateFlowComputerPojo(
        // val flowComputerLinearId: String = "",
        val hostname: String = "",
        val macAddress: String = "",
        val binaryData: String = ""
)