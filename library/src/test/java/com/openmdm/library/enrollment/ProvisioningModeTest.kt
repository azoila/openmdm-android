package com.openmdm.library.enrollment

import android.app.admin.DevicePolicyManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Work-profile provisioning support.
 *
 * OpenMDM began fully-managed-device only — the app is Device Owner and controls
 * the whole device. That is the wrong model for a phone someone also uses
 * personally: you cannot factory-reset an employee's own phone to manage one
 * work app on it. A work profile is the answer, and the mode is chosen at the
 * GET_PROVISIONING_MODE step from what the enrollment config carries. These
 * tests pin that the mode survives the config round-trip and maps to the right
 * platform constant.
 */
@RunWith(RobolectricTestRunner::class)
class ProvisioningModeTest {

    @Test
    fun `parses mode strings, defaulting to fully-managed`() {
        assertThat(ProvisioningMode.fromString("work_profile"))
            .isEqualTo(ProvisioningMode.WORK_PROFILE)
        assertThat(ProvisioningMode.fromString("managed_profile"))
            .isEqualTo(ProvisioningMode.WORK_PROFILE)
        assertThat(ProvisioningMode.fromString("cope"))
            .isEqualTo(ProvisioningMode.COMPANY_OWNED_PERSONALLY_ENABLED)
        // An unrecognised value must not abort enrollment — fall back.
        assertThat(ProvisioningMode.fromString("nonsense"))
            .isEqualTo(ProvisioningMode.FULLY_MANAGED_DEVICE)
        assertThat(ProvisioningMode.fromString(null))
            .isEqualTo(ProvisioningMode.FULLY_MANAGED_DEVICE)
    }

    @Test
    fun `fully-managed answers the device constant`() {
        val result = ManagedProvisioning.provisioningModeResult(
            ProvisioningMode.FULLY_MANAGED_DEVICE,
        )
        assertThat(result.getIntExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, -1))
            .isEqualTo(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
    }

    @Test
    fun `work profile answers the managed-profile constant`() {
        val result = ManagedProvisioning.provisioningModeResult(ProvisioningMode.WORK_PROFILE)
        assertThat(result.getIntExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, -1))
            .isEqualTo(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE)
    }

    @Test
    fun `COPE answers managed-profile too — the org-owned bit is set elsewhere`() {
        // At GET_PROVISIONING_MODE the platform only distinguishes device from
        // profile; the org-owned distinction that makes a profile COPE comes from
        // the provisioning path, not this result.
        val result = ManagedProvisioning.provisioningModeResult(
            ProvisioningMode.COMPANY_OWNED_PERSONALLY_ENABLED,
        )
        assertThat(result.getIntExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, -1))
            .isEqualTo(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE)
    }

    @Test
    fun `builds a work-profile provisioning intent`() {
        val admin = android.content.ComponentName("com.openmdm.agent", "com.openmdm.agent.Receiver")
        val intent = ManagedProvisioning.buildWorkProfileIntent(
            admin,
            EnrollmentConfig(
                serverUrl = "https://mdm.example.com",
                provisioningMode = ProvisioningMode.WORK_PROFILE,
            ),
        )
        // Unlike fully-managed provisioning, this does not need a factory-fresh
        // device — the employee runs it on their own phone.
        assertThat(intent.action).isEqualTo(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
    }

    @Test
    fun `the mode survives the config round-trip through the admin extras bundle`() {
        val admin = android.content.ComponentName("com.openmdm.agent", "com.openmdm.agent.Receiver")
        val intent = ManagedProvisioning.buildWorkProfileIntent(
            admin,
            EnrollmentConfig(
                serverUrl = "https://mdm.example.com",
                enrollmentToken = "PAIR-1",
                provisioningMode = ProvisioningMode.WORK_PROFILE,
            ),
        )

        // This is what onProfileProvisioningComplete reads on the other side. If
        // the mode did not survive, a work-profile enrollment would enable the
        // wrong thing.
        val recovered = ManagedProvisioning.extractConfig(intent)
        assertThat(recovered?.provisioningMode).isEqualTo(ProvisioningMode.WORK_PROFILE)
        assertThat(recovered?.serverUrl).isEqualTo("https://mdm.example.com")
    }

    @Test
    fun `an old config with no mode defaults to fully-managed`() {
        val admin = android.content.ComponentName("com.openmdm.agent", "com.openmdm.agent.Receiver")
        // buildProvisioningIntent is the fully-managed path; a config built
        // without a mode must still round-trip to the historical default.
        val intent = ManagedProvisioning.buildProvisioningIntent(
            admin,
            EnrollmentConfig(serverUrl = "https://mdm.example.com"),
        )

        val recovered = ManagedProvisioning.extractConfig(intent)
        assertThat(recovered?.provisioningMode).isEqualTo(ProvisioningMode.FULLY_MANAGED_DEVICE)
    }

    @Test
    fun `resolveMode honours the desired mode when the platform allows it`() {
        val intent = android.content.Intent().putIntegerArrayListExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES,
            arrayListOf(
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
                DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE,
            ),
        )

        assertThat(ManagedProvisioning.resolveMode(intent, ProvisioningMode.WORK_PROFILE))
            .isEqualTo(ProvisioningMode.WORK_PROFILE)
    }

    @Test
    fun `resolveMode degrades when the platform forbids the desired mode`() {
        // Device launched down the device-owner path: only fully-managed is
        // allowed. A QR asking for a work profile must fall back, not brick setup.
        val intent = android.content.Intent().putIntegerArrayListExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES,
            arrayListOf(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE),
        )

        assertThat(ManagedProvisioning.resolveMode(intent, ProvisioningMode.WORK_PROFILE))
            .isEqualTo(ProvisioningMode.FULLY_MANAGED_DEVICE)
    }

    @Test
    fun `resolveMode passes the desired mode through when the platform states no constraint`() {
        val intent = android.content.Intent() // no allowed-modes extra

        assertThat(ManagedProvisioning.resolveMode(intent, ProvisioningMode.WORK_PROFILE))
            .isEqualTo(ProvisioningMode.WORK_PROFILE)
    }
}
