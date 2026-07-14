package com.openmdm.library.enrollment

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

/**
 * Android Enterprise managed provisioning.
 *
 * ## What this is for
 *
 * A Device Owner cannot be created by an app at runtime. It is established
 * during **provisioning** — the out-of-box flow triggered by a QR code, an NFC
 * bump, `afw#openmdm` typed into the setup wizard, or zero-touch enrollment.
 * The platform hands the DPC a bundle of admin extras and makes it Device Owner;
 * the DPC then has to actually *do* something with those extras.
 *
 * OpenMDM shipped a QR parser that produced an [EnrollmentConfig] and declared
 * every `EXTRA_PROVISIONING_*` constant — and then **never built an Intent, and
 * nothing consumed one**. `onProfileProvisioningComplete` was not overridden. So
 * a device provisioned by QR or zero-touch became Device Owner and then sat
 * there: the server URL, the enrollment token, the policy id all arrived in the
 * admin extras bundle and were dropped on the floor. Provisioning appeared
 * supported and was not.
 *
 * ## The three pieces a DPC must provide
 *
 * 1. **`GET_PROVISIONING_MODE`** activity — the platform asks the DPC what kind
 *    of management it wants (fully managed device vs work profile). **Mandatory
 *    on API 31+**: without it the setup wizard aborts the provisioning, which is
 *    why QR provisioning could not work at all before this.
 * 2. **`ADMIN_POLICY_COMPLIANCE`** activity — the DPC's chance to finish setup
 *    and tell the platform it is satisfied. Also mandatory on API 31+.
 * 3. **`onProfileProvisioningComplete`** — where the admin extras finally arrive.
 *
 * This file provides the library-side helpers; the receiver and the two
 * activities are app-side, because they must reference the app's own components.
 * See the `:agent` module for a reference implementation.
 */
object ManagedProvisioning {

    /**
     * Build the Intent that starts fully-managed-device provisioning.
     *
     * Launch this with `startActivityForResult` from a device that is not yet
     * provisioned — typically from a QR-scanning screen in a custom setup flow.
     * The usual path is that the *platform* starts provisioning from a QR code
     * scanned in the setup wizard, in which case you do not build this Intent at
     * all; it exists for apps driving provisioning themselves.
     */
    fun buildProvisioningIntent(
        adminComponent: ComponentName,
        config: EnrollmentConfig,
    ): Intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE).apply {
        putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
            adminComponent,
        )

        // Everything OpenMDM needs to enroll — server URL, token, policy — rides
        // in the admin extras bundle. This is the *only* channel through which a
        // QR-provisioned device learns which server it belongs to.
        putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            QREnrollmentParser.configToPersistableBundle(config),
        )

        if (config.skipEncryption) {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
        }
        if (config.leaveAllSystemAppsEnabled) {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED,
                true,
            )
        }

        config.wifiConfig?.let { wifi ->
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_WIFI_SSID, wifi.ssid)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_WIFI_HIDDEN, wifi.hidden)
            wifi.securityType?.let {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_WIFI_SECURITY_TYPE, it)
            }
            wifi.password?.let {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_WIFI_PASSWORD, it)
            }
        }
    }

    /**
     * The result a `GET_PROVISIONING_MODE` activity must hand back.
     *
     * OpenMDM manages **fully managed devices** — kiosks, signage, dedicated
     * hardware. It does not manage work profiles on personal phones, so it always
     * answers `PROVISIONING_MODE_FULLY_MANAGED_DEVICE`. An app that wants work
     * profiles builds its own result.
     */
    fun fullyManagedDeviceResult(): Intent = Intent().apply {
        putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
    }

    /**
     * Read the OpenMDM enrollment config out of a provisioning intent.
     *
     * Works for both the `GET_PROVISIONING_MODE` / `ADMIN_POLICY_COMPLIANCE`
     * activity intents and the `onProfileProvisioningComplete` broadcast — the
     * admin extras bundle rides on all three under the same key.
     *
     * Returns `null` when the intent carries no OpenMDM extras, which is the
     * legitimate case for a device provisioned without a server URL (the user
     * will have to enroll by hand).
     */
    fun extractConfig(intent: Intent): EnrollmentConfig? {
        val bundle = adminExtras(intent) ?: return null
        return QREnrollmentParser.bundleToConfig(bundle)
    }

    private fun adminExtras(intent: Intent): PersistableBundle? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java,
            )
        } else {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            )
        }
    }

    /** True when this app is already the Device Owner. */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }
}
