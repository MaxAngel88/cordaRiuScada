package com.riuscada.server.pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class IssueCommandPojo(
        val hostname: String = "",
        val macAddress: String = "",
        val time: String = "",
        val xmlCommandData: String = "",
        val status: String = ""
)