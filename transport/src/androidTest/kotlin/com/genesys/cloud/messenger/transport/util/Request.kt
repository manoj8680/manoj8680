package com.genesys.cloud.messenger.transport.util

internal object Request {
    const val configureRequest =
        """{"token":"00000000-0000-0000-0000-000000000000","deploymentId":"deploymentId","journeyContext":{"customer":{"id":"00000000-0000-0000-0000-000000000000","idType":"cookie"},"customerSession":{"id":"","type":"web"}},"action":"configureSession"}"""
    const val userTypingRequest =
        """{"token":"00000000-0000-0000-0000-000000000000","action":"onMessage","message":{"events":[{"eventType":"Typing","typing":{"type":"On"}}],"type":"Event"}}"""
    const val echoRequest =
        """{"token":"00000000-0000-0000-0000-000000000000","action":"echo","message":{"text":"ping","metadata":{"customMessageId":"SGVhbHRoQ2hlY2tNZXNzYWdlSWQ="},"type":"Text"}}"""
    const val autostart =
        """{"token":"00000000-0000-0000-0000-000000000000","action":"onMessage","message":{"events":[{"eventType":"Presence","presence":{"type":"Join"}}],"type":"Event"}}"""
}