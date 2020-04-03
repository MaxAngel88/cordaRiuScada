package com.riuscada.server.pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class ResponseCounterPojo(
        val outcome : String = "",
        val message : String = "",
        val size : Int = 1,
        val data : Any? = null

)