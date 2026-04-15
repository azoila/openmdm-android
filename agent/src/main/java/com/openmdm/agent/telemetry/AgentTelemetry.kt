package com.openmdm.agent.telemetry

import android.util.Log

/**
 * Structured observability hook for the openmdm agent.
 *
 * The upstream library deliberately does NOT depend on Firebase or
 * any other cloud backend — every agent that builds from this repo
 * would otherwise be forced to pull in Google Play Services and a
 * Firebase project. Instead, this interface is a thin pluggable
 * hook that hosts can replace at `Application.onCreate` time to
 * route lifecycle events and non-fatal exceptions wherever their
 * own telemetry pipeline already goes.
 *
 * The default implementation writes to logcat only. For reference,
 * the midiamob fork installs a Firebase-backed implementation
 * that reports to Crashlytics + Analytics and sets per-device user
 * properties — see
 * `midiamob-solution/apps/mobile/openmdm-android/agent/src/main/java/com/openmdm/agent/telemetry/AgentTelemetry.kt`.
 *
 * ### Call conventions
 *
 * All methods take short `snake_case` event or context names —
 * stable across releases so they can be matched on in dashboards.
 *
 * - **`event(name, params)`** — a notable lifecycle transition.
 *   Examples: `app_boot`, `enrollment_state_loaded`,
 *   `enrollment_state_restored_from_backup`, `heartbeat_result`.
 * - **`nonFatal(t, context, extras)`** — an exception we want to
 *   record but not crash on. Examples: `enrollment_backup_store_init`,
 *   `datastore_read_failed`.
 * - **`breadcrumb(message)`** — chatty trace point. Cheap; do not
 *   use for anything with cardinality.
 */
interface AgentTelemetry {
    /**
     * Called once at process start. Implementations can use this
     * to stamp per-device user properties on their backend. The
     * default impl is a no-op.
     */
    fun initialize() {}

    /** Record a lifecycle event with an optional flat param map. */
    fun event(name: String, params: Map<String, Any?> = emptyMap())

    /** Record a non-fatal exception with a short context tag. */
    fun nonFatal(t: Throwable, context: String, extras: Map<String, Any?> = emptyMap())

    /** Leave a chatty breadcrumb without firing a full event. */
    fun breadcrumb(message: String)
}

/**
 * Default logcat-only implementation. Used upstream and for tests.
 * Hosts should replace this by assigning [AgentTelemetryHolder.install]
 * during `Application.onCreate` before any other agent code runs.
 */
object LogcatAgentTelemetry : AgentTelemetry {
    private const val TAG = "AgentTelemetry"

    override fun event(name: String, params: Map<String, Any?>) {
        val suffix = if (params.isEmpty()) {
            ""
        } else {
            " " + params.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
        Log.i(TAG, "$name$suffix")
    }

    override fun nonFatal(t: Throwable, context: String, extras: Map<String, Any?>) {
        val suffix = if (extras.isEmpty()) {
            ""
        } else {
            " " + extras.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
        Log.w(TAG, "non-fatal [$context]$suffix: ${t.message}", t)
    }

    override fun breadcrumb(message: String) {
        Log.d(TAG, message)
    }
}

/**
 * Static holder so call sites (including places where dependency
 * injection isn't practical, like companion objects) can reach
 * the current telemetry implementation without threading an
 * instance through.
 *
 * Hosts install their own impl at boot:
 *
 * ```kotlin
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         AgentTelemetryHolder.install(MyFirebaseTelemetry(this))
 *     }
 * }
 * ```
 */
object AgentTelemetryHolder {
    @Volatile
    private var current: AgentTelemetry = LogcatAgentTelemetry

    /** Replace the active telemetry implementation. Thread-safe. */
    fun install(impl: AgentTelemetry) {
        current = impl
        impl.initialize()
    }

    /** Reset to the logcat default. Primarily for tests. */
    fun reset() {
        current = LogcatAgentTelemetry
    }

    /** Record a lifecycle event via the active implementation. */
    fun event(name: String, params: Map<String, Any?> = emptyMap()) {
        current.event(name, params)
    }

    /** Record a non-fatal exception via the active implementation. */
    fun nonFatal(t: Throwable, context: String, extras: Map<String, Any?> = emptyMap()) {
        current.nonFatal(t, context, extras)
    }

    /** Leave a breadcrumb via the active implementation. */
    fun breadcrumb(message: String) {
        current.breadcrumb(message)
    }
}
