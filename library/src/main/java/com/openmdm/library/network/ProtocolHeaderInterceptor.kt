package com.openmdm.library.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Stamps every outbound request with the agent wire-protocol version
 * header `X-Openmdm-Protocol: 2`, opting this build into the envelope
 * response shape served by `@openmdm/hono` from 0.3.0 onwards.
 *
 * ## Why this header matters
 *
 * Under protocol v1 (the original behavior), OpenMDM's agent endpoints
 * responded with bare JSON on success and `HTTPException(401|404|5xx)`
 * on failure. The agent had to infer what to do from the HTTP status
 * code — and the obvious interpretation ("401/404 → wipe local state
 * and re-enroll") caused the production auto-unenroll pattern, because
 * a *transient* 401 from a Lambda cold start or a *transient* 404 from
 * an eventual-consistency window was indistinguishable from a real
 * "your enrollment is gone" response.
 *
 * Under protocol v2, every agent endpoint replies with HTTP 200 and a
 * body of shape:
 *
 * ```json
 * { "ok": true,  "action": "none",     "data": { ... } }
 * { "ok": false, "action": "retry",    "message": "..." }
 * { "ok": false, "action": "reauth",   "message": "..." }
 * { "ok": false, "action": "unenroll", "message": "..." }
 * ```
 *
 * The agent reads `action` and has one handler per action — exactly
 * one of which touches enrollment state, and only in the genuine
 * "unenroll" case. Transient 401/404s become `reauth`/`retry` and
 * never wipe local state.
 *
 * ## Rollout caveat
 *
 * Older server deployments that don't recognize the header simply
 * ignore it and serve the legacy v1 shape — but this APK no longer
 * parses v1. If you ship an agent build with this interceptor
 * active against a server that only emits v1, requests will appear
 * to succeed at the HTTP layer but the response parser will fail.
 * **The server side must be able to emit v2 envelopes before this
 * APK is shipped to the fleet.** openmdm server ≥ 0.3.0 is required.
 *
 * The header value must stay in sync with `@openmdm/core`'s
 * `AGENT_PROTOCOL_V2` constant (current value: `"2"`).
 */
// Deliberately NOT annotated with @Inject/@Singleton. The library has no DI
// graph of its own, and a javax.inject annotation on a published AAR is a
// footgun for consumers who do not use Hilt. The agent binds it in its own
// module; a library embedder just constructs it.
class ProtocolHeaderInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
            .newBuilder()
            .header(HEADER_NAME, PROTOCOL_VERSION)
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val HEADER_NAME = "X-Openmdm-Protocol"
        const val PROTOCOL_VERSION = "2"
    }
}
