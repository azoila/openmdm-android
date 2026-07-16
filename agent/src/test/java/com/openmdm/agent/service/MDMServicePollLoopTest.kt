package com.openmdm.agent.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Gate for the in-process command poll loop.
 *
 * The loop exists to collect commands promptly when there is no push channel
 * to wake the device — the demo's "polling" provider. It must be a no-op for
 * real push providers (fcm/mqtt/websocket), where a push wakes the device and
 * the 15-minute WorkManager keep-alive suffices; running it there would add
 * needless heartbeat traffic and battery drain. A null provider (a legacy
 * enrollment predating the persisted field) polls, since a device with no
 * known push channel can only receive commands that way.
 */
class MDMServicePollLoopTest {

    @Test
    fun `polling provider runs the loop`() {
        assertThat(MDMService.shouldRunPollLoop("polling")).isTrue()
    }

    @Test
    fun `null provider runs the loop (legacy or no push channel)`() {
        assertThat(MDMService.shouldRunPollLoop(null)).isTrue()
    }

    @Test
    fun `fcm provider is a no-op`() {
        assertThat(MDMService.shouldRunPollLoop("fcm")).isFalse()
    }

    @Test
    fun `mqtt and websocket providers are no-ops`() {
        assertThat(MDMService.shouldRunPollLoop("mqtt")).isFalse()
        assertThat(MDMService.shouldRunPollLoop("websocket")).isFalse()
    }
}