package com.genesys.cloud.messenger.transport

import com.genesys.cloud.messenger.transport.MessagingClient.State
import com.genesys.cloud.messenger.transport.shyrka.WebMessagingJson
import com.genesys.cloud.messenger.transport.shyrka.receive.AttachmentDeletedResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.DeploymentConfig
import com.genesys.cloud.messenger.transport.shyrka.receive.GenerateUrlError
import com.genesys.cloud.messenger.transport.shyrka.receive.JwtResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.PresignedUrlResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.SessionExpiredEvent
import com.genesys.cloud.messenger.transport.shyrka.receive.SessionResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.StructuredMessage
import com.genesys.cloud.messenger.transport.shyrka.receive.UploadFailureEvent
import com.genesys.cloud.messenger.transport.shyrka.receive.UploadSuccessEvent
import com.genesys.cloud.messenger.transport.shyrka.send.ConfigureSessionRequest
import com.genesys.cloud.messenger.transport.shyrka.send.DeleteAttachmentRequest
import com.genesys.cloud.messenger.transport.shyrka.send.EchoRequest
import com.genesys.cloud.messenger.transport.shyrka.send.GetAttachmentRequest
import com.genesys.cloud.messenger.transport.shyrka.send.GuestInformation
import com.genesys.cloud.messenger.transport.util.ErrorCode
import com.genesys.cloud.messenger.transport.util.Platform
import com.genesys.cloud.messenger.transport.util.PlatformSocket
import com.genesys.cloud.messenger.transport.util.PlatformSocketListener
import com.genesys.cloud.messenger.transport.util.SocketCloseCode
import com.genesys.cloud.messenger.transport.util.extensions.toMessage
import com.genesys.cloud.messenger.transport.util.extensions.toMessageList
import com.genesys.cloud.messenger.transport.util.logs.Log
import com.genesys.cloud.messenger.transport.util.logs.LogTag
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

internal class MessagingClientImpl(
    private val api: WebMessagingApi,
    private val webSocket: PlatformSocket,
    private val configuration: Configuration,
    private val log: Log,
    private val jwtHandler: JwtHandler,
    private val token: String,
    private val attachmentHandler: AttachmentHandler,
    private val messageStore: MessageStore,
) : MessagingClient {

    override var currentState: State = State.Idle
        private set(value) {
            field = value
            stateListener?.invoke(value)
        }

    override var stateListener: ((State) -> Unit)? = null

    override val pendingMessage: Message
        get() = messageStore.pendingMessage

    override val conversation: List<Message>
        get() = messageStore.getConversation()

    @Throws(IllegalStateException::class)
    override fun connect() {
        log.i { "connect()" }
        check(currentState is State.Closed || currentState is State.Idle || currentState is State.Error) { "MessagingClient must be in closed, idle or error state" }
        currentState = State.Connecting
        webSocket.openSocket(socketListener)
    }

    @Throws(IllegalStateException::class)
    override fun disconnect() {
        log.i { "disconnect()" }
        check(currentState !is State.Closed && currentState !is State.Idle) { "MessagingClient must not already be closed or idle" }
        val code = SocketCloseCode.NORMAL_CLOSURE.value
        val reason = "The user has closed the connection."
        currentState = State.Closing(code, reason)
        webSocket.closeSocket(code, reason)
    }

    @Throws(IllegalStateException::class)
    override fun configureNewSession(
        email: String?,
        phoneNumber: String?,
        firstName: String?,
        lastName: String?,
    ) {
        val guestInfo = GuestInformation(
            email = email,
            phoneNumber = phoneNumber,
            firstName = firstName,
            lastName = lastName
        )
        configureSession(guestInformation = guestInfo)
    }

    @Throws(IllegalStateException::class)
    override fun configureSession(
        email: String?,
        phoneNumber: String?,
        firstName: String?,
        lastName: String?,
    ) {
        val guestInfo = GuestInformation(
            email = email,
            phoneNumber = phoneNumber,
            firstName = firstName,
            lastName = lastName
        )
        configureSession(guestInformation = guestInfo)
    }

    private fun configureSession(guestInformation: GuestInformation) {
        log.i { "configureNewSession(token = $token, guestInformation: $guestInformation)" }
        if (currentState !is State.Connected) throw IllegalStateException("WebMessaging client is not connected.")
        val request = ConfigureSessionRequest(
            token = token,
            deploymentId = configuration.deploymentId,
            guestInformation = guestInformation
        )
        val encodedJson = WebMessagingJson.json.encodeToString(request)
        webSocket.sendMessage(encodedJson)
    }

    @Throws(IllegalStateException::class)
    override fun sendMessage(text: String) {
        log.i { "sendMessage(text = $text)" }
        val request = messageStore.prepareMessage(text)
        attachmentHandler.clear()
        val encodedJson = WebMessagingJson.json.encodeToString(request)
        send(encodedJson)
    }

    @Throws(IllegalStateException::class)
    override fun sendHealthCheck() {
        log.i { "sendHealthCheck()" }
        val request = EchoRequest(token = token)
        val encodedJson = WebMessagingJson.json.encodeToString(request)
        send(encodedJson)
    }

    override fun attach(
        byteArray: ByteArray,
        fileName: String,
        uploadProgress: ((Float) -> Unit)?
    ): String {
        log.i { "attach(fileName = $fileName)" }
        val request = attachmentHandler.prepareAttachment(
            Platform().randomUUID(),
            byteArray,
            fileName,
            uploadProgress,
        )
        val encodedJson = WebMessagingJson.json.encodeToString(request)
        send(encodedJson)
        return request.attachmentId
    }

    override fun detach(attachmentId: String) {
        log.i { "detach(attachmentId = $attachmentId)" }
        attachmentHandler.detach(attachmentId) { deleteAttachment(attachmentId) }
    }

    private fun send(message: String) {
        if (currentState !is State.Configured) throw IllegalStateException("WebMessaging client is not configured.")
        log.i { "Will send message" }
        webSocket.sendMessage(message)
    }

    @Throws(IllegalStateException::class)
    override fun generateDownloadUrl(attachmentId: String) {
        log.i { "generateDownloadUrl(attachmentId = $attachmentId)" }
        val request = GetAttachmentRequest(
            token = token,
            attachmentId = attachmentId
        )
    }

    @Throws(IllegalStateException::class)
    override fun deleteAttachment(attachmentId: String) {
        log.i { "deleteAttachment(attachmentId = $attachmentId)" }
        val request = DeleteAttachmentRequest(
            token = token,
            attachmentId = attachmentId
        )
        val encodedJson = WebMessagingJson.json.encodeToString(request)
        send(encodedJson)
    }

    override suspend fun fetchNextPage() {
        check(currentState is State.Configured) { "WebMessaging client is not configured." }
        if (messageStore.startOfConversation) {
            log.i { "All history have been fetched." }
            return
        }
        log.i { "fetching history for page index = ${messageStore.nextPage}" }
        jwtHandler.withJwt { jwt -> api.getMessages(jwt, messageStore.nextPage) }
            .also {
                messageStore.updateMessageHistory(
                    it.toMessageList(),
                    it.total,
                )
            }
    }

    override suspend fun fetchDeploymentConfig(): DeploymentConfig {
        return api.fetchDeploymentConfig()
    }

    private fun handleError(code: ErrorCode, message: String? = null) {
        if (code == ErrorCode.SessionHasExpired || code == ErrorCode.SessionNotFound) {
            currentState = State.Error(code, message)
        } else if (code == ErrorCode.MessageTooLong) {
            messageStore.onMessageError(code, message)
        }
    }

    private val socketListener = SocketListener(
        log = log.withTag(LogTag.WEBSOCKET)
    )

    private inner class SocketListener(
        private val log: Log,
    ) : PlatformSocketListener {

        override fun onOpen() {
            log.i { "onOpen()" }
            currentState = State.Connected
        }

        override fun onFailure(t: Throwable) {
            log.e(throwable = t) { "onFailure(message: ${t.message})" }
            currentState = State.Error(ErrorCode.WebsocketError, t.message)
            webSocket.closeSocket(SocketCloseCode.GOING_AWAY.value, "Going away.")
        }

        override fun onMessage(text: String) {
            log.i { "onMessage(text = $text)" }
            try {
                val decoded = WebMessagingJson.decodeFromString(text)
                when (decoded.body) {
                    is String -> handleError(ErrorCode.mapFrom(decoded.code), decoded.body)
                    is SessionExpiredEvent -> handleError(ErrorCode.SessionHasExpired)
                    is SessionResponse -> {
                        decoded.body.run {
                            currentState = State.Configured(connected, newSession)
                        }
                    }
                    is JwtResponse ->
                        jwtHandler.jwtResponse = decoded.body
                    is PresignedUrlResponse ->
                        attachmentHandler.upload(decoded.body)
                    is UploadSuccessEvent ->
                        attachmentHandler.uploadSuccess(decoded.body)
                    is StructuredMessage ->
                        messageStore.update(decoded.body.toMessage())
                    is AttachmentDeletedResponse ->
                        attachmentHandler.onDeleted(decoded.body.attachmentId)
                    is GenerateUrlError -> {
                        decoded.body.run {
                            attachmentHandler.onError(
                                attachmentId,
                                ErrorCode.mapFrom(errorCode),
                                errorMessage
                            )
                        }
                    }
                    is UploadFailureEvent -> {
                        decoded.body.run {
                            attachmentHandler.onError(
                                attachmentId,
                                ErrorCode.mapFrom(errorCode),
                                errorMessage
                            )
                        }
                    }
                }
            } catch (exception: SerializationException) {
                log.e(throwable = exception) { "Failed to deserialize message" }
            } catch (exception: IllegalArgumentException) {
                log.e(throwable = exception) { "Message decoded as null" }
            }
        }

        override fun onClosing(code: Int, reason: String) {
            log.i { "onClosing(code = $code, reason = $reason)" }
            currentState = State.Closing(code, reason)
        }

        override fun onClosed(code: Int, reason: String) {
            log.i { "onClosed(code = $code, reason = $reason)" }
            currentState = State.Closed(code, reason)
        }
    }
}