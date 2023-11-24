package com.genesys.cloud.messenger.transport.core

import kotlinx.serialization.Serializable

@Serializable
data class ButtonResponse(
    val text: String,
    val payload: String,
    val type: String,
)
