package com.genesys.cloud.messenger.transport.shyrka.receive

import com.genesys.cloud.messenger.transport.core.Message
import com.genesys.cloud.messenger.transport.shyrka.send.HealthCheckID
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class MessageEntityList(
    val entities: List<StructuredMessage> = emptyList(),
    val pageSize: Int,
    val pageNumber: Int,
    val total: Int,
    val pageCount: Int,
)

@Serializable
internal data class StructuredMessage(
    val id: String,
    val type: Type,
    val text: String? = null,
    val direction: String,
    val channel: Channel? = null,
    val content: List<Content> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val events: List<StructuredMessageEvent> = emptyList(),
    val originatingEntity: String? = null,
) {
    @Serializable
    data class Participant(
        val firstName: String? = null,
        val lastName: String? = null,
        val nickname: String? = null,
        val image: String? = null,
    )

    @Serializable
    data class Channel(
        val time: String? = null,
        val messageId: String? = null,
        val type: String? = null,
        val to: Participant? = null,
        val from: Participant? = null,
    )

    @Serializable
    enum class Type {
        @SerialName("Text")
        Text,
        @SerialName("Event")
        Event,
        @SerialName("Structured")
        Structured,
    }

    @Serializable(with = ContentSerializer::class)
    internal sealed class Content {
        @Serializable
        enum class Type {
            Attachment,
        }

        @Serializable
        data class AttachmentContent(
            val contentType: String,
            val attachment: Attachment,
        ) : Content() {
            @Serializable
            data class Attachment(
                val id: String,
                val url: String,
                val filename: String,
                val fileSize: Int? = null,
                val mediaType: String,
                val mime: String? = null,
                val sha256: String? = null,
                val text: String? = null,
            )
        }

        @Serializable
        internal object UnknownContent : Content()
    }

    internal object ContentSerializer :
        JsonContentPolymorphicSerializer<Content>(Content::class) {
        override fun selectDeserializer(element: JsonElement): DeserializationStrategy<out Content> {
            return when (element.jsonObject["contentType"]?.jsonPrimitive?.content) {
                Content.Type.Attachment.name -> Content.AttachmentContent.serializer()
                else -> Content.UnknownContent.serializer()
            }
        }
    }
}

internal fun StructuredMessage.isOutbound(): Boolean =
    this.direction == Message.Direction.Outbound.name

internal fun StructuredMessage.isInbound(): Boolean =
    this.direction == Message.Direction.Inbound.name

internal fun StructuredMessage.isHealthCheckResponse(): Boolean =
    this.metadata["customMessageId"] == HealthCheckID
