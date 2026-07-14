package com.openmdm.library.network

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The protocol-v2 header is what stops an agent self-wiping on a transient error.
 *
 * Under v1, a 401 or 404 from the server was indistinguishable from "you have
 * been unenrolled", and cheap kiosk hardware duly wiped itself over a blip.
 * Under v2 the server answers HTTP 200 with an explicit `action`, so "refresh
 * your token" and "you are genuinely gone" are different messages. That only
 * works if the header is actually on the request — which nothing tested until
 * now.
 */
class ProtocolHeaderInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(ProtocolHeaderInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stamps the protocol header on every request`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/agent/heartbeat")).build()).execute()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader(ProtocolHeaderInterceptor.HEADER_NAME)).isEqualTo("2")
    }

    @Test
    fun `header name and version match the server contract`() {
        // Must stay in lockstep with @openmdm/core's AGENT_PROTOCOL_HEADER and
        // AGENT_PROTOCOL_V2. A silent drift here means the agent asks for v1
        // semantics and starts self-wiping on 401s again.
        assertThat(ProtocolHeaderInterceptor.HEADER_NAME).isEqualTo("X-Openmdm-Protocol")
        assertThat(ProtocolHeaderInterceptor.PROTOCOL_VERSION).isEqualTo("2")
    }

    @Test
    fun `overwrites a stale header rather than appending`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder()
                .url(server.url("/agent/heartbeat"))
                .header(ProtocolHeaderInterceptor.HEADER_NAME, "1")
                .build(),
        ).execute()

        val recorded = server.takeRequest()
        assertThat(recorded.headers.values(ProtocolHeaderInterceptor.HEADER_NAME))
            .containsExactly("2")
    }
}
