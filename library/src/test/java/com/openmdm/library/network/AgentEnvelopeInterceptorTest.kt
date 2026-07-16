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
 * The envelope decoder is what makes the v2 opt-in actually work.
 *
 * The agent stamps `X-Openmdm-Protocol: 2` on every request, so the
 * server answers with `{ok, action, data}` envelopes. Without this
 * interceptor those envelopes were parsed against the flat payload
 * models — every field landed null and enrollment crashed with an NPE
 * on `deviceId` (found live on the emulator). These tests pin both
 * halves of the decode: success envelopes unwrap to `data`, failure
 * envelopes map to the same legacy statuses the server uses for v1
 * clients, with the action exposed in `X-Openmdm-Action`.
 */
class AgentEnvelopeInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(ProtocolHeaderInterceptor())
            .addInterceptor(AgentEnvelopeInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    private fun execute() = client.newCall(
        Request.Builder().url(server.url("/agent/enroll")).build()
    ).execute()

    @Test
    fun `success envelope is unwrapped to its data payload`() {
        enqueueJson("""{"ok":true,"action":"none","data":{"deviceId":"dev-1","token":"t"}}""")

        val response = execute()

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string())
            .isEqualTo("""{"deviceId":"dev-1","token":"t"}""")
        assertThat(response.header(AgentEnvelopeInterceptor.ACTION_HEADER)).isNull()
    }

    @Test
    fun `success envelope with no data unwraps to an empty object`() {
        enqueueJson("""{"ok":true,"action":"none"}""")

        val response = execute()

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string()).isEqualTo("{}")
    }

    @Test
    fun `reauth envelope maps to 401 with the action header`() {
        enqueueJson("""{"ok":false,"action":"reauth","message":"token expired"}""")

        val response = execute()

        assertThat(response.code).isEqualTo(401)
        assertThat(response.header(AgentEnvelopeInterceptor.ACTION_HEADER)).isEqualTo("reauth")
        assertThat(response.body?.string()).contains("token expired")
    }

    @Test
    fun `unenroll envelope maps to 404 with the action header`() {
        enqueueJson("""{"ok":false,"action":"unenroll"}""")

        val response = execute()

        assertThat(response.code).isEqualTo(404)
        assertThat(response.header(AgentEnvelopeInterceptor.ACTION_HEADER)).isEqualTo("unenroll")
    }

    @Test
    fun `retry envelope maps to 503 with the action header`() {
        enqueueJson("""{"ok":false,"action":"retry","message":"db hiccup"}""")

        val response = execute()

        assertThat(response.code).isEqualTo(503)
        assertThat(response.header(AgentEnvelopeInterceptor.ACTION_HEADER)).isEqualTo("retry")
    }

    @Test
    fun `unknown future action maps to retryable 503`() {
        enqueueJson("""{"ok":false,"action":"rebind"}""")

        val response = execute()

        assertThat(response.code).isEqualTo(503)
        assertThat(response.header(AgentEnvelopeInterceptor.ACTION_HEADER)).isEqualTo("rebind")
    }

    @Test
    fun `bare v1 body passes through untouched`() {
        // The challenge endpoint (and any v1-only server) answers flat
        // JSON even when the request carries the v2 header.
        val bare = """{"challenge":"abc","expiresAt":"2026-07-16T11:06:16.859Z","ttlSeconds":300}"""
        enqueueJson(bare)

        val response = execute()

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string()).isEqualTo(bare)
    }

    @Test
    fun `json that merely mentions ok is not mistaken for an envelope`() {
        // "ok" must be a boolean AND "action" a string for the body to
        // count as an envelope.
        val bare = """{"ok":"yes","status":"fine"}"""
        enqueueJson(bare)

        val response = execute()

        assertThat(response.body?.string()).isEqualTo(bare)
    }

    @Test
    fun `non-json responses pass through untouched`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody("pong")
        )

        val response = execute()

        assertThat(response.body?.string()).isEqualTo("pong")
    }

    @Test
    fun `requests without the v2 header are not decoded`() {
        val noHeaderClient = OkHttpClient.Builder()
            .addInterceptor(AgentEnvelopeInterceptor())
            .build()
        val envelope = """{"ok":false,"action":"reauth"}"""
        enqueueJson(envelope)

        val response = noHeaderClient.newCall(
            Request.Builder().url(server.url("/agent/enroll")).build()
        ).execute()

        // A request that never opted into v2 should never have its
        // response rewritten, even if the body looks like an envelope.
        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string()).isEqualTo(envelope)
    }

    @Test
    fun `real server 5xx is left for the retry layer, not decoded`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"boom"}""")
        )

        val response = execute()

        assertThat(response.code).isEqualTo(500)
        assertThat(response.header(AgentEnvelopeInterceptor.ACTION_HEADER)).isNull()
    }
}