package com.openmdm.agent.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The service-start safety net for a provisioning enrollment that never landed.
 *
 * Provisioning queues enrollment from inside the setup wizard, on a device that
 * may not have a network yet and whose work can go away with the rest of setup.
 * The failure that leaves behind is the worst one available: fully managed,
 * knows its server, never talks to it. Every service start — so every boot —
 * re-arms it.
 *
 * The gate is deliberately narrow. A device with no provisioned server URL has
 * no server to reach, and re-queueing work for it would retry forever against a
 * build-time default nobody asked for.
 */
class MDMServiceProvisioningRecoveryTest {

    @Test
    fun `provisioned but not enrolled is re-armed`() {
        assertThat(
            MDMService.shouldRecoverProvisioningEnrollment(
                isEnrolled = false,
                hasProvisionedServer = true,
            ),
        ).isTrue()
    }

    @Test
    fun `an enrolled device is left alone`() {
        assertThat(
            MDMService.shouldRecoverProvisioningEnrollment(
                isEnrolled = true,
                hasProvisionedServer = true,
            ),
        ).isFalse()
    }

    @Test
    fun `a device that was never provisioned has no server to reach`() {
        assertThat(
            MDMService.shouldRecoverProvisioningEnrollment(
                isEnrolled = false,
                hasProvisionedServer = false,
            ),
        ).isFalse()
    }
}
