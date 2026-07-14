package com.openmdm.agent.telemetry

import com.openmdm.library.telemetry.LogcatMdmTelemetry
import com.openmdm.library.telemetry.MdmTelemetry
import com.openmdm.library.telemetry.MdmTelemetryHolder

/**
 * Compatibility shim.
 *
 * The telemetry hook now lives in `:library`
 * ([com.openmdm.library.telemetry.MdmTelemetry]), because the library's own
 * security code — Keystore-backed enrollment, TLS pinning, the encrypted
 * enrollment backup — needs to report what it is doing, and it cannot reach
 * into `:agent` to do so.
 *
 * These aliases exist so the agent's call sites keep compiling. New code should
 * use the library types directly.
 */
typealias AgentTelemetry = MdmTelemetry

/** @see com.openmdm.library.telemetry.LogcatMdmTelemetry */
val LogcatAgentTelemetry: MdmTelemetry = LogcatMdmTelemetry

/**
 * Delegates to the library's holder. Installing here installs there — there is
 * one telemetry pipeline, not two.
 */
object AgentTelemetryHolder {
    fun install(impl: AgentTelemetry) = MdmTelemetryHolder.install(impl)

    fun reset() = MdmTelemetryHolder.reset()

    fun event(name: String, params: Map<String, Any?> = emptyMap()) =
        MdmTelemetryHolder.event(name, params)

    fun nonFatal(t: Throwable, context: String, extras: Map<String, Any?> = emptyMap()) =
        MdmTelemetryHolder.nonFatal(t, context, extras)

    fun breadcrumb(message: String) = MdmTelemetryHolder.breadcrumb(message)
}
