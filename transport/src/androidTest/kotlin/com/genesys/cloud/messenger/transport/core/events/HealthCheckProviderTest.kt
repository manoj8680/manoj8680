package com.genesys.cloud.messenger.transport.core.events

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.genesys.cloud.messenger.transport.util.Platform
import com.genesys.cloud.messenger.transport.util.Request
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Test

class HealthCheckProviderTest {
    private val mockTimestampFunction: () -> Long = spyk<() -> Long>().also {
        every { it.invoke() } answers { Platform().epochMillis() }
    }

    private val subject = HealthCheckProvider(mockk(relaxed = true), mockTimestampFunction)

    @Test
    fun whenEncode() {
        val expected = Request.echo
        val result = subject.encodeRequest(token = Request.token)

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun whenEncodeWithCoolDown() {
        val healthCheckCoolDownInMilliseconds = HEALTH_CHECK_COOL_DOWN_MILLISECONDS + 250
        val expected = Request.echo
        val firstResult = subject.encodeRequest(token = Request.token)
        every { mockTimestampFunction.invoke() } answers { Platform().epochMillis() + healthCheckCoolDownInMilliseconds }
        val secondResult = subject.encodeRequest(token = Request.token)

        assertThat(firstResult).isEqualTo(expected)
        assertThat(secondResult).isEqualTo(expected)
    }

    @Test
    fun whenEncodeWithoutCoolDown() {
        val expected = Request.echo
        val firstResult = subject.encodeRequest(token = Request.token)
        val secondResult = subject.encodeRequest(token = Request.token)

        assertThat(firstResult).isEqualTo(expected)
        assertThat(secondResult).isNull()
    }

    @Test
    fun whenEncodeWithoutCoolDownButWithClear() {
        val expected = Request.echo
        val firstResult = subject.encodeRequest(token = Request.token)
        subject.clear()
        val secondResult = subject.encodeRequest(token = Request.token)

        assertThat(firstResult).isEqualTo(expected)
        assertThat(secondResult).isEqualTo(expected)
    }
}
