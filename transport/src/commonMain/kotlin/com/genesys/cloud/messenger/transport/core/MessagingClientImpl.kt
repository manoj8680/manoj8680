package com.genesys.cloud.messenger.transport.core

import com.genesys.cloud.messenger.transport.auth.AuthHandler
import com.genesys.cloud.messenger.transport.auth.AuthHandlerImpl
import com.genesys.cloud.messenger.transport.auth.NO_JWT
import com.genesys.cloud.messenger.transport.core.MessagingClient.State
import com.genesys.cloud.messenger.transport.core.events.Event
import com.genesys.cloud.messenger.transport.core.events.EventHandler
import com.genesys.cloud.messenger.transport.core.events.EventHandlerImpl
import com.genesys.cloud.messenger.transport.core.events.HealthCheckProvider
import com.genesys.cloud.messenger.transport.core.events.UserTypingProvider
import com.genesys.cloud.messenger.transport.network.PlatformSocket
import com.genesys.cloud.messenger.transport.network.PlatformSocketListener
import com.genesys.cloud.messenger.transport.network.ReconnectionHandler
import com.genesys.cloud.messenger.transport.network.SocketCloseCode
import com.genesys.cloud.messenger.transport.network.WebMessagingApi
import com.genesys.cloud.messenger.transport.shyrka.WebMessagingJson
import com.genesys.cloud.messenger.transport.shyrka.receive.AttachmentDeletedResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.ConnectionClosedEvent
import com.genesys.cloud.messenger.transport.shyrka.receive.DeploymentConfig
import com.genesys.cloud.messenger.transport.shyrka.receive.GenerateUrlError
import com.genesys.cloud.messenger.transport.shyrka.receive.JwtResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.LogoutEvent
import com.genesys.cloud.messenger.transport.shyrka.receive.PresignedUrlResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.SessionClearedEvent
import com.genesys.cloud.messenger.transport.shyrka.receive.SessionExpiredEvent
import com.genesys.cloud.messenger.transport.shyrka.receive.SessionResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.StructuredMessage
import com.genesys.cloud.messenger.transport.shyrka.receive.TooManyRequestsErrorMessage
import com.genesys.cloud.messenger.transport.shyrka.receive.UploadFailureEvent
import com.genesys.cloud.messenger.transport.shyrka.receive.UploadSuccessEvent
import com.genesys.cloud.messenger.transport.shyrka.send.AutoStartRequest
import com.genesys.cloud.messenger.transport.shyrka.send.Channel
import com.genesys.cloud.messenger.transport.shyrka.send.ClearConversationRequest
import com.genesys.cloud.messenger.transport.shyrka.send.CloseSessionRequest
import com.genesys.cloud.messenger.transport.shyrka.send.ConfigureAuthenticatedSessionRequest
import com.genesys.cloud.messenger.transport.shyrka.send.ConfigureSessionRequest
import com.genesys.cloud.messenger.transport.shyrka.send.JourneyContext
import com.genesys.cloud.messenger.transport.shyrka.send.JourneyCustomer
import com.genesys.cloud.messenger.transport.shyrka.send.JourneyCustomerSession
import com.genesys.cloud.messenger.transport.util.Platform
import com.genesys.cloud.messenger.transport.util.Vault
import com.genesys.cloud.messenger.transport.util.extensions.isHealthCheckResponseId
import com.genesys.cloud.messenger.transport.util.extensions.isOutbound
import com.genesys.cloud.messenger.transport.util.extensions.toMessage
import com.genesys.cloud.messenger.transport.util.extensions.toMessageList
import com.genesys.cloud.messenger.transport.util.logs.Log
import com.genesys.cloud.messenger.transport.util.logs.LogTag
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.reflect.KProperty0

private const val MAX_RECONFIGURE_ATTEMPTS = 3

internal class MessagingClientImpl(
    vault: Vault,
    private val api: WebMessagingApi,
    private val webSocket: PlatformSocket,
    private val configuration: Configuration,
    private val log: Log,
    private val jwtHandler: JwtHandler,
    private val token: String,
    private val deploymentConfig: KProperty0<DeploymentConfig?>,
    private val attachmentHandler: AttachmentHandler,
    private val messageStore: MessageStore,
    private val reconnectionHandler: ReconnectionHandler,
    private val stateMachine: StateMachine = StateMachineImpl(log.withTag(LogTag.STATE_MACHINE)),
    private val eventHandler: EventHandler = EventHandlerImpl(log.withTag(LogTag.EVENT_HANDLER)),
    private val healthCheckProvider: HealthCheckProvider = HealthCheckProvider(log.withTag(LogTag.HEALTH_CHECK_PROVIDER)),
    private val userTypingProvider: UserTypingProvider = UserTypingProvider(
        log.withTag(LogTag.TYPING_INDICATOR_PROVIDER),
        { deploymentConfig.isShowUserTypingEnabled() },
    ),
    private val authHandler: AuthHandler = AuthHandlerImpl(
        configuration.autoRefreshTokenWhenExpired,
        eventHandler,
        api,
        vault,
        log.withTag(LogTag.AUTH_HANDLER)
    ),
    private val internalCustomAttributesStore: CustomAttributesStoreImpl = CustomAttributesStoreImpl(
        log.withTag(LogTag.CUSTOM_ATTRIBUTES_STORE)
    ),
) : MessagingClient {
    private var connectAuthenticated = false
    private var isStartingANewSession = false
    private var reconfigureAttempts = 0
    private var sendingAutostart = false

    override val currentState: State
        get() {
            return stateMachine.currentState
        }

    override var stateChangedListener: ((StateChange) -> Unit)? = null
        set(value) {
            stateMachine.stateChangedListener = value
            field = value
        }

    override var messageListener: ((MessageEvent) -> Unit)? = null
        set(value) {
            messageStore.messageListener = value
            field = value
        }

    override var eventListener: ((Event) -> Unit)? = null
        set(value) {
            eventHandler.eventListener = value
            field = value
        }

    override val pendingMessage: Message
        get() = messageStore.pendingMessage

    override val conversation: List<Message>
        get() = messageStore.getConversation()

    override val customAttributesStore: CustomAttributesStore
        get() = internalCustomAttributesStore

    @Throws(IllegalStateException::class)
    override fun connect() {
        log.i { "connect()" }
        connectAuthenticated = false
        stateMachine.onConnect()
        webSocket.openSocket(socketListener)
    }

    @Throws(IllegalStateException::class)
    override fun connectAuthenticatedSession() {
        log.i { "connectAuthenticatedSession()" }
        connectAuthenticated = true
        stateMachine.onConnect()
        webSocket.openSocket(socketListener)
    }

    @Throws(IllegalStateException::class)
    override fun startNewChat() {
        stateMachine.checkIfCanStartANewChat()
        isStartingANewSession = true
        closeAllConnectionsForTheSession()
    }

    @Throws(IllegalStateException::class)
    override fun disconnect() {
        log.i { "disconnect()" }
        val code = SocketCloseCode.NORMAL_CLOSURE.value
        val reason = "The user has closed the connection."
        reconnectionHandler.clear()
        stateMachine.onClosing(code, reason)
        webSocket.closeSocket(code, reason)
    }

    private fun configureSession(startNew: Boolean = false) {
        val encodedJson = if (connectAuthenticated) {
            log.i { "configureAuthenticatedSession(token = $token, startNew: $startNew)" }
            if (authHandler.jwt == NO_JWT) {
                if (reconfigureAttempts < MAX_RECONFIGURE_ATTEMPTS) {
                    reconfigureAttempts++
                    refreshTokenAndPerform { configureSession(startNew) }
                    return
                }
                transitionToStateError(ErrorCode.AuthFailed, ErrorMessage.FailedToConfigureSession)
                return
            }
            encodeAuthenticatedConfigureSessionRequest(startNew)
        } else {
            log.i { "configureSession(token = $token, startNew: $startNew)" }
            encodeAnonymousConfigureSessionRequest(startNew)
        }
        webSocket.sendMessage(encodedJson)
    }

    @Throws(IllegalStateException::class)
    override fun sendMessage(text: String, customAttributes: Map<String, String>) {
        stateMachine.checkIfConfigured()
        log.i { "sendMessage(text = $text, customAttributes = $customAttributes)" }
        internalCustomAttributesStore.add(customAttributes)
        val channel = prepareCustomAttributesForSending()
        val request = messageStore.prepareMessage(text, channel)
        attachmentHandler.onSending()
        val encodedJson = WebMessagingJson.json.encodeToString(request)
        send(encodedJson)
    }

    override fun sendQuickReply(buttonResponse: ButtonResponse) {
        stateMachine.checkIfConfigured()
        log.i { "sendQuickReply(buttonResponse: $buttonResponse)" }
        val channel = prepareCustomAttributesForSending()
        val request = messageStore.prepareMessageWith(buttonResponse, channel)
        val encodedJson = WebMessagingJson.json.encodeToString(request)
        send(encodedJson)
    }

    @Throws(IllegalStateException::class)
    override fun sendHealthCheck() {
        healthCheckProvider.encodeRequest(token)?.let {
            log.i { "sendHealthCheck()" }
            send(it)
        }
    }

    override fun attach(
        byteArray: ByteArray,
        fileName: String,
        uploadProgress: ((Float) -> Unit)?,
    ): String {
        log.i { "attach(fileName = $fileName)" }
        val request = attachmentHandler.prepare(
            Platform().randomUUID(),
            byteArray,
            fileName,
            uploadProgress,
        )
        val encodedJson = WebMessagingJson.json.encodeToString(request)
        send(encodedJson)
        return request.attachmentId
    }

    @Throws(IllegalStateException::class)
    override fun detach(attachmentId: String) {
        log.i { "detach(attachmentId = $attachmentId)" }
        attachmentHandler.detach(attachmentId)?.let {
            val encodedJson = WebMessagingJson.json.encodeToString(it)
            send(encodedJson)
        }
    }

    @Throws(IllegalStateException::class)
    private fun send(message: String) {
        stateMachine.checkIfConfigured()
        log.i { "Will send message" }
        webSocket.sendMessage(message)
    }

    @Throws(Exception::class)
    override suspend fun fetchNextPage() {
        stateMachine.checkIfConfiguredOrReadOnly()
        if (messageStore.startOfConversation) {
            log.i { "All history has been fetched." }
            messageStore.updateMessageHistory(emptyList(), conversation.size)
            return
        }
        log.i { "fetching history for page index = ${messageStore.nextPage}" }
        jwtHandler.withJwt { jwt ->
            api.getMessages(jwt, messageStore.nextPage).also {
                when (it) {
                    is Result.Success -> {
                        messageStore.updateMessageHistory(
                            it.value.entities.toMessageList(),
                            it.value.total,
                        )
                    }

                    is Result.Failure -> {
                        if (it.errorCode is ErrorCode.CancellationError) {
                            log.w { "Cancellation exception was thrown, while running getMessages() request." }
                            return
                        }
                        log.w { "History fetch failed with: $it" }
                        eventHandler.onEvent(
                            Event.Error(
                                ErrorCode.HistoryFetchFailure,
                                it.message,
                                it.errorCode.toCorrectiveAction()
                            )
                        )
                    }
                }
            }
        }
    }

    @Throws(IllegalStateException::class)
    override fun logoutFromAuthenticatedSession() {
        stateMachine.checkIfConfigured()
        authHandler.logout()
    }

    @Throws(IllegalStateException::class)
    override fun clearConversation() {
        stateMachine.checkIfConfiguredOrReadOnly()
        if (!deploymentConfig.isClearConversationEnabled()) {
            eventHandler.onEvent(
                Event.Error(
                    ErrorCode.ClearConversationFailure,
                    ErrorMessage.FailedToClearConversation,
                    CorrectiveAction.Forbidden
                )
            )
            return
        }
        WebMessagingJson.json.encodeToString(ClearConversationRequest(token)).let {
            log.i { "sendClearConversation()" }
            webSocket.sendMessage(it)
        }
    }

    override fun invalidateConversationCache() {
        log.i { "Clear conversation history." }
        messageStore.invalidateConversationCache()
    }

    @Throws(IllegalStateException::class)
    override fun indicateTyping() {
        userTypingProvider.encodeRequest(token)?.let {
            log.i { "indicateTyping()" }
            send(it)
        }
    }

    override fun authorize(authCode: String, redirectUri: String, codeVerifier: String?) {
        authHandler.authorize(authCode, redirectUri, codeVerifier)
    }

    @Throws(IllegalStateException::class)
    private fun sendAutoStart() {
        sendingAutostart = true
        val channel = prepareCustomAttributesForSending()
        WebMessagingJson.json.encodeToString(AutoStartRequest(token, channel)).let {
            log.i { "sendAutoStart()" }
            send(it)
        }
    }

    /**
     * This function executes [CloseSessionRequest]. This request will trigger closure of
     * all opened connections on all other devices that share the same session token,
     * by sending them a [ConnectionClosedEvent].
     * After successful closure an event [SessionResponse] with `connected=false` will be received,
     * indicating that new chat should be configured.
     */
    private fun closeAllConnectionsForTheSession() {
        WebMessagingJson.json.encodeToString(
            CloseSessionRequest(
                token = token,
                closeAllConnections = true
            )
        ).also {
            log.i { "closeSession()" }
            webSocket.sendMessage(it)
        }
    }

    private fun handleError(code: ErrorCode, message: String? = null) {
        when (code) {
            is ErrorCode.SessionHasExpired,
            is ErrorCode.SessionNotFound,
            -> transitionToStateError(code, message)

            is ErrorCode.MessageTooLong,
            is ErrorCode.RequestRateTooHigh,
            is ErrorCode.CustomAttributeSizeTooLarge,
            -> {
                if (code is ErrorCode.CustomAttributeSizeTooLarge) {
                    internalCustomAttributesStore.onError()
                    if (sendingAutostart) {
                        sendingAutostart = false
                        eventHandler.onEvent(
                            Event.Error(
                                code,
                                message,
                                code.toCorrectiveAction()
                            )
                        )
                    }
                } else {
                    internalCustomAttributesStore.onMessageError()
                }
                messageStore.onMessageError(code, message)
                attachmentHandler.onMessageError(code, message)
            }

            is ErrorCode.ClientResponseError,
            is ErrorCode.ServerResponseError,
            is ErrorCode.RedirectResponseError,
            -> {
                if (stateMachine.isConnected() || stateMachine.isReconnecting() || isStartingANewSession) {
                    handleConfigureSessionErrorResponse(code, message)
                } else {
                    handleConventionalHttpErrorResponse(code, message)
                }
            }

            is ErrorCode.WebsocketError -> handleWebSocketError(ErrorCode.WebsocketError)
            else -> log.w { "Unhandled ErrorCode: $code with optional message: $message" }
        }
    }

    private fun refreshTokenAndPerform(action: () -> Unit) {
        authHandler.refreshToken { result ->
            when (result) {
                is Result.Success -> action()
                is Result.Failure -> transitionToStateError(result.errorCode, result.message)
            }
        }
    }

    private fun handleWebSocketError(errorCode: ErrorCode) {
        considerForceClose()
        if (stateMachine.isInactive()) return
        invalidateConversationCache()
        when (errorCode) {
            is ErrorCode.WebsocketError -> {
                if (reconnectionHandler.shouldReconnect) {
                    stateMachine.onReconnect()
                    reconnectionHandler.reconnect { if (connectAuthenticated) connectAuthenticatedSession() else connect() }
                } else {
                    transitionToStateError(errorCode, ErrorMessage.FailedToReconnect)
                }
            }

            is ErrorCode.WebsocketAccessDenied -> {
                transitionToStateError(errorCode, CorrectiveAction.Forbidden.message)
            }

            is ErrorCode.NetworkDisabled -> {
                transitionToStateError(errorCode, ErrorMessage.InternetConnectionIsOffline)
            }

            else -> log.w { "Unhandled WebSocket errorCode. ErrorCode: $errorCode" }
        }
    }

    private fun handleConfigureSessionErrorResponse(code: ErrorCode, message: String?) {
        if (connectAuthenticated && code.isUnauthorized() && reconfigureAttempts < MAX_RECONFIGURE_ATTEMPTS) {
            reconfigureAttempts++
            if (stateMachine.isConnected()) {
                refreshTokenAndPerform { configureSession(isStartingANewSession) }
            } else {
                refreshTokenAndPerform { connectAuthenticatedSession() }
            }
        } else {
            transitionToStateError(code, message)
        }
    }

    private fun handleConventionalHttpErrorResponse(code: ErrorCode, message: String?) {
        val errorCode =
            if (message.isClearConversationError()) ErrorCode.ClearConversationFailure else code
        eventHandler.onEvent(
            Event.Error(
                errorCode = errorCode,
                message = message,
                correctiveAction = code.toCorrectiveAction()
            )
        )
    }

    private fun Message.handleAsTextMessage() {
        if (id.isHealthCheckResponseId()) {
            eventHandler.onEvent(Event.HealthChecked)
            return
        }
        messageStore.update(this)
        if (direction == Message.Direction.Inbound) {
            internalCustomAttributesStore.onSent()
            attachmentHandler.onSent(attachments)
            userTypingProvider.clear()
        }
    }

    private fun Message.handleAsEvent(isReadOnly: Boolean) = this.run {
        if (isOutbound()) {
            // Every Outbound event should be reported to UI.
            events.forEach {
                if (it is Event.ConversationDisconnect && isReadOnly) stateMachine.onReadOnly()
                eventHandler.onEvent(it)
            }
        } else {
            // Autostart is the only Inbound event that should be reported to UI.
            events.find { it is Event.ConversationAutostart }?.let { autostart ->
                sendingAutostart = false
                internalCustomAttributesStore.onSent()
                eventHandler.onEvent(autostart)
            }
        }
    }

    private fun Message.handleAsStructuredMessage() {
        when (messageType) {
            Message.Type.QuickReply -> messageStore.update(this)
            Message.Type.Unknown -> log.w { "Unknown messages of type Structure are not supported." }
            else -> log.w { "Should not happen." }
        }
    }

    private fun cleanUp() {
        invalidateConversationCache()
        userTypingProvider.clear()
        healthCheckProvider.clear()
        attachmentHandler.clearAll()
        reconnectionHandler.clear()
        reconfigureAttempts = 0
        sendingAutostart = false
        internalCustomAttributesStore.onSessionClosed()
    }

    private fun transitionToStateError(errorCode: ErrorCode, errorMessage: String?) {
        stateMachine.onError(errorCode, errorMessage)
        attachmentHandler.clearAll()
        reconnectionHandler.clear()
        reconfigureAttempts = 0
        sendingAutostart = false
    }

    private fun considerForceClose() {
        if (stateMachine.isClosing()) {
            log.i { "Force close web socket." }
            val closingState = stateMachine.currentState as State.Closing
            socketListener.onClosed(closingState.code, closingState.reason)
        }
    }

    private fun encodeAnonymousConfigureSessionRequest(startNew: Boolean) =
        WebMessagingJson.json.encodeToString(
            ConfigureSessionRequest(
                token = token,
                deploymentId = configuration.deploymentId,
                startNew = startNew,
                journeyContext = JourneyContext(
                    JourneyCustomer(token, "cookie"),
                    JourneyCustomerSession("", "web")
                )
            )
        )

    private fun encodeAuthenticatedConfigureSessionRequest(startNew: Boolean) =
        WebMessagingJson.json.encodeToString(
            ConfigureAuthenticatedSessionRequest(
                token = token,
                deploymentId = configuration.deploymentId,
                startNew = startNew,
                journeyContext = JourneyContext(
                    JourneyCustomer(token, "cookie"),
                    JourneyCustomerSession("", "web")
                ),
                data = ConfigureAuthenticatedSessionRequest.Data(authHandler.jwt)
            )
        )

    private fun prepareCustomAttributesForSending(): Channel? =
        internalCustomAttributesStore.getCustomAttributesToSend().asChannel()?.also {
            internalCustomAttributesStore.onSending()
        }

    private val socketListener = SocketListener(
        log = log.withTag(LogTag.WEBSOCKET)
    )

    private inner class SocketListener(
        private val log: Log,
    ) : PlatformSocketListener {

        override fun onOpen() {
            log.i { "onOpen()" }
            stateMachine.onConnectionOpened()
            configureSession()
        }

        override fun onFailure(t: Throwable, errorCode: ErrorCode) {
            log.e(throwable = t) { "onFailure(message: ${t.message})" }
            handleWebSocketError(errorCode)
        }

        override fun onMessage(text: String) {
            log.i { "onMessage(text = $text)" }
            try {
                val decoded = WebMessagingJson.decodeFromString(text)
                when (decoded.body) {
                    is String -> handleError(ErrorCode.mapFrom(decoded.code), decoded.body)
                    is SessionExpiredEvent -> handleError(ErrorCode.SessionHasExpired)
                    is TooManyRequestsErrorMessage -> {
                        handleError(
                            ErrorCode.RequestRateTooHigh,
                            "${decoded.body.errorMessage}. Retry after ${decoded.body.retryAfter} seconds."
                        )
                    }

                    is SessionResponse -> {
                        decoded.body.run {
                            reconnectionHandler.clear()
                            if (readOnly) {
                                stateMachine.onReadOnly()
                                if (!connected && isStartingANewSession) {
                                    cleanUp()
                                    configureSession(startNew = true)
                                }
                            } else {
                                isStartingANewSession = false
                                stateMachine.onSessionConfigured(connected, newSession)
                                if (newSession && deploymentConfig.isAutostartEnabled()) {
                                    sendAutoStart()
                                }
                            }
                        }
                    }

                    is JwtResponse ->
                        jwtHandler.jwtResponse = decoded.body

                    is PresignedUrlResponse ->
                        attachmentHandler.upload(decoded.body)

                    is UploadSuccessEvent ->
                        attachmentHandler.onUploadSuccess(decoded.body)

                    is StructuredMessage -> {
                        decoded.body.run {
                            val message = this.toMessage()
                            when (type) {
                                StructuredMessage.Type.Text -> message.handleAsTextMessage()
                                StructuredMessage.Type.Event -> message.handleAsEvent(
                                    metadata["readOnly"].toBoolean()
                                )

                                StructuredMessage.Type.Structured -> message.handleAsStructuredMessage()
                            }
                        }
                    }

                    is AttachmentDeletedResponse ->
                        attachmentHandler.onDetached(decoded.body.attachmentId)

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

                    is ConnectionClosedEvent -> {
                        disconnect()
                        eventHandler.onEvent(Event.ConnectionClosed)
                    }

                    is LogoutEvent -> {
                        authHandler.clear()
                        eventHandler.onEvent(Event.Logout)
                        disconnect()
                    }

                    is SessionClearedEvent -> eventHandler.onEvent(Event.ConversationCleared)
                    else -> {
                        log.i { "Unhandled message received from Shyrka: $decoded " }
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
            stateMachine.onClosing(code, reason)
        }

        override fun onClosed(code: Int, reason: String) {
            log.i { "onClosed(code = $code, reason = $reason)" }
            stateMachine.onClosed(code, reason)
            cleanUp()
        }
    }
}

/**
 * Checks if the string contains both the words "conversation clear" in exact order (case-insensitive).
 *
 * @return `true` if the string contains "conversation clear", `false` otherwise.
 */
private fun String?.isClearConversationError(): Boolean {
    val regex = Regex("Conversation Clear", RegexOption.IGNORE_CASE)
    return this?.let { regex.containsMatchIn(it) } ?: false
}

private fun KProperty0<DeploymentConfig?>.isAutostartEnabled(): Boolean =
    this.get()?.messenger?.apps?.conversations?.autoStart?.enabled == true

private fun KProperty0<DeploymentConfig?>.isShowUserTypingEnabled(): Boolean =
    this.get()?.messenger?.apps?.conversations?.showUserTypingIndicator == true

private fun KProperty0<DeploymentConfig?>.isClearConversationEnabled(): Boolean =
    this.get()?.messenger?.apps?.conversations?.conversationClear?.enabled == true

private fun Map<String, String>.asChannel(): Channel? {
    return if (this.isNotEmpty()) {
        Channel(Channel.Metadata(this))
    } else null
}
