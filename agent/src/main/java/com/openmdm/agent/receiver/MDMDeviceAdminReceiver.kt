package com.openmdm.agent.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.openmdm.agent.R
import com.openmdm.agent.provisioning.ProvisioningHandoff
import com.openmdm.library.device.WorkProfileManager
import com.openmdm.library.telemetry.MdmTelemetryHolder

/**
 * Device Admin Receiver for MDM policies
 *
 * Handles device administrator events and enables device management features
 * such as password policies, wipe, and lock.
 */
class MDMDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(
            context,
            "Device admin enabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "Device admin disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.device_admin_description)
    }

    /**
     * The platform has finished provisioning us as Device Owner.
     *
     * This carries the admin extras bundle — the server URL, the enrollment
     * token, the policy id that the operator embedded in the QR code or
     * zero-touch configuration.
     *
     * It is no longer the *only* channel: on API 31+ this broadcast is sent at
     * the setup wizard's finalization step, which a wizard that aborts earlier
     * never reaches, so [ProvisioningHandoff] is also driven from
     * [com.openmdm.agent.provisioning.PolicyComplianceActivity]. Whichever
     * arrives first wins; adoption is idempotent, so both arriving is fine.
     *
     * We persist the config and hand off to WorkManager rather than enrolling
     * inline: a broadcast receiver has a few seconds before the system may kill
     * it, and enrollment involves key generation, a challenge round-trip, and a
     * signed POST. Doing that here would work on a fast network and fail
     * silently on a slow one — the worst kind of bug to have at provisioning
     * time, because the device is now Device Owner and nobody is watching.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)

        val config = ProvisioningHandoff.adopt(
            context,
            intent,
            ProvisioningHandoff.SOURCE_PROVISIONING_COMPLETE,
        )

        if (config?.serverUrl == null) {
            // Legitimate: a device provisioned without OpenMDM extras. It is
            // Device Owner, but nobody told it where to report. Enrollment will
            // have to be driven by hand.
            Log.w(
                TAG,
                "Provisioning completed with no OpenMDM server URL in the admin extras. " +
                    "The device is Device Owner but cannot enroll on its own.",
            )
            MdmTelemetryHolder.event(
                "provisioning_complete",
                mapOf("has_server_url" to false),
            )
            return
        }

        // A work profile is created *disabled*. Until we enable it, its apps do
        // not appear in the launcher and the user is left with a half-set-up
        // phone. This is the one step a work-profile provision must not skip —
        // and it is a no-op on a fully-managed device (we are Device Owner
        // there, not Profile Owner), so it is safe to attempt unconditionally.
        if (workProfileManagerOrNull(context)?.let { it.isProfileOwner() } == true) {
            WorkProfileManager.create(context, getComponentName(context))
                .enableProfile()
                .onFailure { Log.w(TAG, "Failed to enable work profile", it) }
        }

        MdmTelemetryHolder.event(
            "provisioning_complete",
            mapOf(
                "has_server_url" to true,
                "has_enrollment_token" to (config.enrollmentToken != null),
                "has_policy_id" to (config.policyId != null),
            ),
        )
    }

    override fun onPasswordChanged(context: Context, intent: Intent, userHandle: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, userHandle)
        // Report password change event
    }

    override fun onPasswordFailed(context: Context, intent: Intent, userHandle: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, userHandle)
        // Report failed password attempt
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, userHandle: android.os.UserHandle) {
        super.onPasswordSucceeded(context, intent, userHandle)
        // Report successful unlock
    }

    private fun workProfileManagerOrNull(context: Context): WorkProfileManager? =
        runCatching { WorkProfileManager.create(context, getComponentName(context)) }.getOrNull()

    companion object {
        private const val TAG = "MDMDeviceAdmin"

        fun getComponentName(context: Context): android.content.ComponentName {
            return android.content.ComponentName(context, MDMDeviceAdminReceiver::class.java)
        }
    }
}
