package com.openmdm.library.network

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Decodes the protocol-v2 response envelope back into the flat shape
 * the agent's Retrofit models expect.
 *
 * [ProtocolHeaderInterceptor] opts every request into protocol v2, under
 * which agent endpoints answer HTTP 200 with:
 *
 * ```json
 * { "ok": true,  "action": "none",     "data": { ... } }
 * { "ok": false, "action": "retry",    "message": "..." }
 * { "ok": false, "action": "reauth",   "message": "..." }
 * { "ok": false, "action": "unenroll", "message": "..." }
 * ```
 *
 * Sending the header without decoding the envelope is worse than not
 * sending it at all: Gson parses the envelope root against the payload
 * model, every field silently lands null, and the failure surfaces as a
 * NullPointerException far from the network layer (as it did in
 * enrollment). This interceptor is the decoding half of the v2 opt-in:
 *
 * - `ok: true` → the response body is replaced with `data`, so the
 *   existing payload models parse unchanged.
 * - `ok: false` → the response is rewritten with the same legacy status
 *   the server would have used for a v1 client (`reauth` → 401,
 *   `unenroll` → 404, `retry` → 503), and the action is exposed
 *   explicitly in the [ACTION_HEADER] response header so callers can
 *   branch on the server's instruction instead of re-inferring it from
 *   the status code. Existing error handling keeps working; new code
 *   should read the header.
 * - Anything that is not an envelope (bare v1 bodies, the challenge
 *   endpoint, non-JSON) passes through untouched, so the client works
 *   against both v1-only and v2 servers.
 *
 * Unknown future actions map to 503/`retry`: the one always-safe
 * reaction to an instruction this build does not understand is "try
 * again later without touching local state".
 *
 * Must be installed AFTER any retry interceptor (closer to the network),
 * so a decoded `retry` surfaces to it as a retryable 503.
 */
// Deliberately NOT annotated with @Inject/@Singleton — see
// ProtocolHeaderInterceptor for why the library stays DI-free.
class AgentEnvelopeInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // Only requests that opted into v2 can receive envelopes.
        val optedIn = chain.request().header(ProtocolHeaderInterceptor.HEADER_NAME) ==
            ProtocolHeaderInterceptor.PROTOCOL_VERSION
        if (!optedIn || !response.isSuccessful) return response

        val body = response.body ?: return response
        val contentType = body.contentType()
        if (contentType?.subtype?.contains("json") != true) return response

        // Reading the body consumes it; every path below must rebuild it.
        val raw = body.string()

        return when (val decoded = decode(raw)) {
            null -> response.newBuilder()
                .body(raw.toResponseBody(contentType))
                .build()

            is Decoded.Success -> response.newBuilder()
                .body(decoded.dataJson.toResponseBody(contentType))
                .build()

            is Decoded.Failure -> response.newBuilder()
                .code(decoded.legacyStatus)
                .message(decoded.action)
                .header(ACTION_HEADER, decoded.action)
                .body(decoded.errorJson.toResponseBody(contentType))
                .build()
        }
    }

    internal sealed class Decoded {
        data class Success(val dataJson: String) : Decoded()
        data class Failure(
            val action: String,
            val legacyStatus: Int,
            val errorJson: String,
        ) : Decoded()
    }

    companion object {
        /**
         * Response header carrying the server's `action` on a decoded
         * failure envelope. Absent on success and on non-envelope
         * responses.
         */
        const val ACTION_HEADER = "X-Openmdm-Action"

        /**
         * Decode an envelope body, or return null when the body is not
         * an envelope (bare v1 payloads pass through unchanged).
         *
         * The envelope signature is a JSON object with a boolean `ok`
         * and a string `action` — both always present per the server's
         * `AgentResponse` type in `@openmdm/core`.
         */
        internal fun decode(raw: String): Decoded? {
            val root = try {
                JsonParser.parseString(raw)
            } catch (_: JsonSyntaxException) {
                return null
            }
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject

            val ok = obj.get("ok")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?: return null
            val action = obj.get("action")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?: return null

            if (ok.asBoolean) {
                val data = obj.get("data")
                return Decoded.Success(if (data == null || data.isJsonNull) "{}" else data.toString())
            }

            val actionValue = action.asString
            val message = obj.get("message")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?: defaultMessageFor(actionValue)
            val error = com.google.gson.JsonObject().apply {
                addProperty("error", message)
                addProperty("action", actionValue)
            }
            return Decoded.Failure(
                action = actionValue,
                legacyStatus = legacyStatusFor(actionValue),
                errorJson = error.toString(),
            )
        }

        /**
         * The same action→status mapping the server applies for v1
         * clients (`agentFailResponse` in `@openmdm/hono`). Unknown
         * actions are treated as `retry`.
         */
        private fun legacyStatusFor(action: String): Int = when (action) {
            "reauth" -> 401
            "unenroll" -> 404
            else -> 503 // "retry" and anything this build doesn't know
        }

        private fun defaultMessageFor(action: String): String = when (action) {
            "reauth" -> "Device authentication required"
            "unenroll" -> "Device not found"
            else -> "Temporarily unavailable"
        }
    }
}
