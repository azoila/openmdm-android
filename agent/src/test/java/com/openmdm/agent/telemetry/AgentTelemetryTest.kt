package com.openmdm.agent.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests for [AgentTelemetry] + [AgentTelemetryHolder].
 *
 * The holder is a static piece that hosts replace at process start,
 * so the test surface is small but load-bearing: if the holder ever
 * stops routing calls to the installed impl, every downstream consumer
 * (MDMRepository, EnrollmentBackupStore, etc.) silently loses its
 * diagnostic breadcrumbs in production without anyone noticing.
 *
 * Runs under Robolectric so `LogcatAgentTelemetry`'s `android.util.Log`
 * calls are stubbed. The holder-routing tests could run on plain JVM,
 * but keeping everything under one runner makes the file simpler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentTelemetryTest {

    @After
    fun tearDown() {
        AgentTelemetryHolder.reset()
    }

    /**
     * Test implementation that records every call into lists so we can
     * assert on them.
     */
    private class RecordingTelemetry : AgentTelemetry {
        val initializations = mutableListOf<Unit>()
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val nonFatals = mutableListOf<Triple<Throwable, String, Map<String, Any?>>>()
        val breadcrumbs = mutableListOf<String>()

        override fun initialize() {
            initializations.add(Unit)
        }

        override fun event(name: String, params: Map<String, Any?>) {
            events.add(name to params)
        }

        override fun nonFatal(t: Throwable, context: String, extras: Map<String, Any?>) {
            nonFatals.add(Triple(t, context, extras))
        }

        override fun breadcrumb(message: String) {
            breadcrumbs.add(message)
        }
    }

    // ============================================
    // AgentTelemetryHolder — routing contract
    // ============================================

    @Test
    fun `holder routes event calls to the installed implementation`() {
        val recording = RecordingTelemetry()
        AgentTelemetryHolder.install(recording)

        AgentTelemetryHolder.event("enrollment_state_loaded", mapOf("source" to "primary"))

        assertThat(recording.events).hasSize(1)
        assertThat(recording.events[0].first).isEqualTo("enrollment_state_loaded")
        assertThat(recording.events[0].second).containsEntry("source", "primary")
    }

    @Test
    fun `holder routes nonFatal calls to the installed implementation`() {
        val recording = RecordingTelemetry()
        AgentTelemetryHolder.install(recording)

        val error = IllegalStateException("boom")
        AgentTelemetryHolder.nonFatal(error, "test_context", mapOf("attempt" to 3))

        assertThat(recording.nonFatals).hasSize(1)
        val (thrown, context, extras) = recording.nonFatals[0]
        assertThat(thrown).isSameInstanceAs(error)
        assertThat(context).isEqualTo("test_context")
        assertThat(extras).containsEntry("attempt", 3)
    }

    @Test
    fun `holder routes breadcrumb calls to the installed implementation`() {
        val recording = RecordingTelemetry()
        AgentTelemetryHolder.install(recording)

        AgentTelemetryHolder.breadcrumb("reached stage 2")

        assertThat(recording.breadcrumbs).containsExactly("reached stage 2")
    }

    @Test
    fun `install calls initialize on the new implementation`() {
        // Hosts use initialize() to stamp per-device user properties on
        // their backend. If install() forgets to call it, those
        // properties never get set and reports aren't grouped per device.
        val recording = RecordingTelemetry()
        AgentTelemetryHolder.install(recording)

        assertThat(recording.initializations).hasSize(1)
    }

    @Test
    fun `reset returns the holder to the logcat default`() {
        val recording = RecordingTelemetry()
        AgentTelemetryHolder.install(recording)

        AgentTelemetryHolder.reset()

        // After reset, further calls should NOT reach the recording
        // impl — they route to LogcatAgentTelemetry instead.
        AgentTelemetryHolder.event("post_reset_event")
        assertThat(recording.events).isEmpty()
    }

    @Test
    fun `installing a second implementation replaces the first`() {
        val first = RecordingTelemetry()
        val second = RecordingTelemetry()
        AgentTelemetryHolder.install(first)
        AgentTelemetryHolder.install(second)

        AgentTelemetryHolder.event("after_swap")

        assertThat(first.events).isEmpty()
        assertThat(second.events).hasSize(1)
    }

    // ============================================
    // LogcatAgentTelemetry — default behavior
    // ============================================

    @Test
    fun `LogcatAgentTelemetry event does not throw`() {
        // We're not on a real Android runtime so we can't assert on
        // logcat content, but we can verify the default impl doesn't
        // throw on any of its call shapes. The logcat-only default
        // must never be a hot-path liability.
        LogcatAgentTelemetry.event("test", mapOf("k" to "v"))
        LogcatAgentTelemetry.event("no_params")
    }

    @Test
    fun `LogcatAgentTelemetry nonFatal does not throw`() {
        LogcatAgentTelemetry.nonFatal(
            IllegalStateException("test"),
            "test_context",
            mapOf("k" to "v"),
        )
        LogcatAgentTelemetry.nonFatal(RuntimeException("minimal"), "ctx")
    }

    @Test
    fun `LogcatAgentTelemetry breadcrumb does not throw`() {
        LogcatAgentTelemetry.breadcrumb("test breadcrumb")
    }
}
