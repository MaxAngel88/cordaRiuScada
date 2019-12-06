package com.riuscada.server.pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class IssueFlowComputerPojo(
        val hostname: String = "",
        val macAddress: String = "",
        val binaryData: String = ""
)