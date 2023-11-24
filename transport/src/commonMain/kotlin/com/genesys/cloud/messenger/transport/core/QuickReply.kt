package com.genesys.cloud.messenger.transport.core

import kotlinx.serialization.Serializable

@Serializable
data class QuickReply(
    val text: String,
    val payload: String,
    val action: String,
)
