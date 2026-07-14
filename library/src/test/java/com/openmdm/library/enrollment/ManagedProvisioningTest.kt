package com.openmdm.library.enrollment

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.PersistableBundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Managed provisioning.
 *
 * The agent shipped a complete QR *parser* — it read every provisioning format
 * and declared every `EXTRA_PROVISIONING_*` constant — and then never built an
 * Intent, and nothing ever consumed one. `onProfileProvisioningComplete` was not
 * overridden. So a device provisioned by QR or zero-touch became Device Owner
 * and then sat there, with the server URL and enrollment token dropped on the
 * floor. Provisioning looked supported and was not.
 *
 * These tests pin the round-trip that makes it real: config → admin extras
 * bundle → provisioning intent → back to config on the other side.
 */
@RunWith(RobolectricTestRunner::class)
class ManagedProvisioningTest {

    private val admin = ComponentName("com.openmdm.agent", "com.openmdm.agent.Receiver")

    private fun config() = EnrollmentConfig(
        serverUrl = "https://mdm.example.com",
        deviceSecret = "s3cret",
        enrollmentToken = "PAIR-1234",
        policyId = "kiosk",
        groupId = "fleet-a",
    )

    @Test
    fun `builds a fully-managed-device provisioning intent`() {
        val intent = ManagedProvisioning.buildProvisioningIntent(admin, config())

        assertThat(intent.action).isEqualTo(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)
        assertThat(
            intent.getParcelableExtra<ComponentName>(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
            ),
        ).isEqualTo(admin)
    }

    @Test
    fun `carries the OpenMDM config in the admin extras bundle`() {
        // The admin extras bundle is the ONLY channel through which a
        // QR-provisioned device learns which server it belongs to.
        val intent = ManagedProvisioning.buildProvisioningIntent(admin, config())

        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )

        assertThat(extras).isNotNull()
        assertThat(extras!!.getString("openmdm.server_url")).isEqualTo("https://mdm.example.com")
        assertThat(extras.getString("openmdm.enrollment_token")).isEqualTo("PAIR-1234")
    }

    @Test
    fun `round-trips the config through the intent`() {
        val intent = ManagedProvisioning.buildProvisioningIntent(admin, config())

        // This is what onProfileProvisioningComplete does on the other side.
        val recovered = ManagedProvisioning.extractConfig(intent)

        assertThat(recovered).isNotNull()
        assertThat(recovered!!.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(recovered.deviceSecret).isEqualTo("s3cret")
        assertThat(recovered.enrollmentToken).isEqualTo("PAIR-1234")
        assertThat(recovered.policyId).isEqualTo("kiosk")
        assertThat(recovered.groupId).isEqualTo("fleet-a")
    }

    @Test
    fun `answers the platform with fully-managed-device mode`() {
        val result = ManagedProvisioning.fullyManagedDeviceResult()

        // Returning the wrong mode — or no result at all — aborts provisioning.
        assertThat(
            result.getIntExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, -1),
        ).isEqualTo(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
    }

    @Test
    fun `extracting from an intent with no extras returns null`() {
        // Legitimate: a device provisioned without OpenMDM extras is Device Owner
        // but has nobody to report to. It must not crash the receiver.
        val bare = android.content.Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)

        assertThat(ManagedProvisioning.extractConfig(bare)).isNull()
    }

    @Test
    fun `wifi config rides along when supplied`() {
        val withWifi = config().copy(
            wifiConfig = WifiProvisioningConfig(
                ssid = "Depot",
                hidden = true,
                securityType = "WPA",
                password = "hunter2",
            ),
        )

        val intent = ManagedProvisioning.buildProvisioningIntent(admin, withWifi)

        // A device provisioned in a warehouse needs the network to reach the
        // server before it can enroll at all.
        assertThat(
            intent.getStringExtra(DevicePolicyManager.EXTRA_PROVISIONING_WIFI_SSID),
        ).isEqualTo("Depot")
        assertThat(
            intent.getBooleanExtra(DevicePolicyManager.EXTRA_PROVISIONING_WIFI_HIDDEN, false),
        ).isTrue()
    }
}
