package com.genesys.cloud.messenger.transport.core.messagingclient

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.genesys.cloud.messenger.transport.core.Attachment
import com.genesys.cloud.messenger.transport.core.ErrorCode
import com.genesys.cloud.messenger.transport.core.Message
import com.genesys.cloud.messenger.transport.shyrka.receive.PresignedUrlResponse
import com.genesys.cloud.messenger.transport.shyrka.receive.UploadSuccessEvent
import com.genesys.cloud.messenger.transport.core.Message.Direction
import com.genesys.cloud.messenger.transport.core.Message.State
import com.genesys.cloud.messenger.transport.core.Message.Type
import com.genesys.cloud.messenger.transport.util.Request
import com.genesys.cloud.messenger.transport.util.Response
import com.genesys.cloud.messenger.transport.utility.AttachmentValues
import com.genesys.cloud.messenger.transport.utility.ErrorTest
import com.genesys.cloud.messenger.transport.utility.LogMessages
import com.genesys.cloud.messenger.transport.utility.TestValues
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MCAttachmentTests : BaseMessagingClientTest() {

    @Test
    fun `when attach`() {
        val expectedAttachmentId = "88888888-8888-8888-8888-888888888888"
        val expectedMessage =
            """{"token":"${Request.token}","attachmentId":"88888888-8888-8888-8888-888888888888","fileName":"test_attachment.png","fileType":"image/png","errorsAsJson":true,"action":"onAttachment"}"""
        subject.connect()

        val result = subject.attach(ByteArray(1), "test.png")

        assertEquals(expectedAttachmentId, result)
        verifySequence {
            connectSequence()
            mockLogger.i(capture(logSlot))
            mockAttachmentHandler.prepare(any(), any(), any())
            mockLogger.i(capture(logSlot))
            mockPlatformSocket.sendMessage(expectedMessage)
        }
        assertThat(logSlot[0].invoke()).isEqualTo(LogMessages.Connect)
        assertThat(logSlot[1].invoke()).isEqualTo(LogMessages.ConfigureSession)
        assertThat(logSlot[2].invoke()).isEqualTo(LogMessages.Attach)
        assertThat(logSlot[3].invoke()).isEqualTo(LogMessages.WillSendMessage)
    }

    @Test
    fun `when detach`() {
        val expectedAttachmentId = "88888888-8888-8888-8888-888888888888"
        val expectedMessage =
            """{"token":"${Request.token}","attachmentId":"88888888-8888-8888-8888-888888888888","action":"deleteAttachment"}"""
        val attachmentIdSlot = slot<String>()
        subject.connect()

        subject.detach("88888888-8888-8888-8888-888888888888")

        verify {
            mockLogger.i(capture(logSlot))
            mockAttachmentHandler.detach(capture(attachmentIdSlot))
            mockPlatformSocket.sendMessage(expectedMessage)
        }
        assertThat(attachmentIdSlot.captured).isEqualTo(expectedAttachmentId)
        assertThat(logSlot[0].invoke()).isEqualTo(LogMessages.Connect)
        assertThat(logSlot[1].invoke()).isEqualTo(LogMessages.ConfigureSession)
        assertThat(logSlot[2].invoke()).isEqualTo(LogMessages.Detach)
    }

    @Test
    fun `when detach non existing attachmentId`() {
        subject.connect()
        clearMocks(mockPlatformSocket)
        every { mockAttachmentHandler.detach(any()) } returns null

        subject.detach("88888888-8888-8888-8888-888888888888")

        verify {
            mockAttachmentHandler.detach("88888888-8888-8888-8888-888888888888")
            mockPlatformSocket wasNot Called
        }
    }

    @Test
    fun `when SocketListener invoke OnMessage with AttachmentDeleted response`() {
        val expectedAttachmentId = "attachment_id"

        subject.connect()

        slot.captured.onMessage(Response.attachmentDeleted)

        verifySequence {
            connectSequence()
            mockAttachmentHandler.onDetached(expectedAttachmentId)
        }
    }

    @Test
    fun `when attach without connection`() {
        assertFailsWith<IllegalStateException> {
            subject.attach(ByteArray(1), "file.png")
        }
    }

    @Test
    fun `when detach attachment without connection`() {
        assertFailsWith<IllegalStateException> {
            subject.detach("attachmentId")
        }
    }

    @Test
    fun `when SocketListener invoke onMessage with Inbound StructuredMessage that contains attachment`() {
        val expectedAttachment = Attachment(
            "attachment_id",
            "image.png",
            Attachment.State.Sent("https://downloadurl.com")
        )
        val expectedMessage = Message(
            "msg_id",
            Direction.Inbound,
            State.Sent,
            Type.Text,
            "Text",
            "Hi",
            null,
            mapOf("attachment_id" to expectedAttachment)
        )

        subject.connect()

        slot.captured.onMessage(Response.onMessageWithAttachment(Direction.Inbound))

        verifySequence {
            connectSequence()
            mockMessageStore.update(expectedMessage)
            mockCustomAttributesStore.onSent()
            mockAttachmentHandler.onSent(mapOf("attachment_id" to expectedAttachment))
        }
    }

    @Test
    fun `when SocketListener invoke onMessage with Outbound StructuredMessage that contains attachment`() {
        val expectedAttachment = Attachment(
            "attachment_id",
            "image.png",
            Attachment.State.Sent("https://downloadurl.com")
        )
        val expectedMessage = Message(
            "msg_id",
            Direction.Outbound,
            State.Sent,
            Type.Text,
            "Text",
            "Hi",
            null,
            mapOf("attachment_id" to expectedAttachment)
        )

        subject.connect()

        slot.captured.onMessage(Response.onMessageWithAttachment(Direction.Outbound))

        verifySequence {
            connectSequence()
            mockMessageStore.update(expectedMessage)
        }

        verify(exactly = 0) {
            mockCustomAttributesStore.onSent()
            mockAttachmentHandler.onSent(any())
        }
    }

    @Test
    fun `when SocketListener invoke OnMessage with UploadSuccessEvent response`() {
        val expectedEvent = UploadSuccessEvent(
            attachmentId = AttachmentValues.Id,
            downloadUrl = AttachmentValues.DownloadUrl,
            timestamp = TestValues.Timestamp,
        )

        subject.connect()

        slot.captured.onMessage(Response.uploadSuccessEvent)

        verifySequence {
            connectSequence()
            mockAttachmentHandler.onUploadSuccess(expectedEvent)
        }
    }

    @Test
    fun `when SocketListener invoke OnMessage with PresignedUrlResponse response`() {
        val expectedEvent = PresignedUrlResponse(
            attachmentId = AttachmentValues.Id,
            headers = mapOf(AttachmentValues.PresignedHeaderKey to AttachmentValues.PresignedHeaderValue),
            url = AttachmentValues.DownloadUrl,
        )

        subject.connect()

        slot.captured.onMessage(Response.presignedUrlResponse)

        verifySequence {
            connectSequence()
            mockAttachmentHandler.upload(expectedEvent)
        }
    }

    @Test
    fun `when SocketListener invoke OnMessage with GenerateUrlError response`() {
        subject.connect()

        slot.captured.onMessage(Response.generateUrlError)

        verifySequence {
            connectSequence()
            mockAttachmentHandler.onError(AttachmentValues.Id, ErrorCode.FileTypeInvalid, ErrorTest.Message)
        }
    }

    @Test
    fun `when SocketListener invoke OnMessage with UploadFailureEvent response`() {
        subject.connect()

        slot.captured.onMessage(Response.uploadFailureEvent)

        verifySequence {
            connectSequence()
            mockAttachmentHandler.onError(AttachmentValues.Id, ErrorCode.FileTypeInvalid, ErrorTest.Message)
        }
    }
}
