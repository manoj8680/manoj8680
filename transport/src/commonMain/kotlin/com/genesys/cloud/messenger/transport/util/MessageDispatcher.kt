package com.genesys.cloud.messenger.transport.util

import com.genesys.cloud.messenger.transport.MessageEvent

expect class MessageDispatcher {
    fun dispatch(event: MessageEvent)
}

interface MessageListener {
    fun onEvent(event: MessageEvent)
}