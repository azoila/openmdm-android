package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lock-task dedup tests for [DeviceManager].
 *
 * `DevicePolicyManager.setLockTaskPackages` rejects a duplicated entry with
 * "duplicate element". Both lock-task entry points build their package array
 * by unconditionally appending the agent's own package, so a single-app kiosk
 * whose target IS the agent — or a caller that already includes it — used to
 * fail with that error. Found live: an `enterKiosk` command targeting
 * com.openmdm.agent completed with `success=false, "duplicate element"`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceManagerLockTaskTest {

    private lateinit var context: Context
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var packageManager: PackageManager
    private lateinit var adminComponent: ComponentName
    private lateinit var deviceManager: DeviceManager

    private val agentPackage = "com.openmdm.agent"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        devicePolicyManager = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        val powerManager = mockk<PowerManager>(relaxed = true)
        adminComponent = ComponentName(agentPackage, "TestReceiver")

        every { context.packageName } returns agentPackage
        every { context.getSystemService(Context.DEVICE_POLICY_SERVICE) } returns devicePolicyManager
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { context.packageManager } returns packageManager
        every { devicePolicyManager.isDeviceOwnerApp(any()) } returns true
        // A non-null launch intent so startLockTaskMode reaches the DPM call.
        every { packageManager.getLaunchIntentForPackage(any()) } returns mockk(relaxed = true)

        deviceManager = DeviceManager.create(context, adminComponent)
    }

    private fun capturedLockTaskPackages(block: () -> Unit): Array<String> {
        val slot = slot<Array<String>>()
        every { devicePolicyManager.setLockTaskPackages(adminComponent, capture(slot)) } returns Unit
        block()
        return slot.captured
    }

    @Test
    fun `startLockTaskMode on the agent itself does not duplicate the package`() {
        val packages = capturedLockTaskPackages {
            val result = deviceManager.startLockTaskMode(agentPackage)
            assertThat(result.isSuccess).isTrue()
        }

        // The agent package must appear exactly once — a duplicate is what the
        // framework rejects.
        assertThat(packages.toList()).containsExactly(agentPackage)
    }

    @Test
    fun `startLockTaskMode on another app includes both packages`() {
        val target = "com.example.kiosk"
        val packages = capturedLockTaskPackages {
            assertThat(deviceManager.startLockTaskMode(target).isSuccess).isTrue()
        }

        assertThat(packages.toList()).containsExactly(target, agentPackage)
    }

    @Test
    fun `setLockTaskPackages dedupes repeated and agent entries`() {
        val packages = capturedLockTaskPackages {
            val result = deviceManager.setLockTaskPackages(
                listOf("com.a", "com.a", agentPackage, "com.b"),
            )
            assertThat(result.isSuccess).isTrue()
        }

        assertThat(packages.toList()).containsExactly("com.a", agentPackage, "com.b").inOrder()
    }
}