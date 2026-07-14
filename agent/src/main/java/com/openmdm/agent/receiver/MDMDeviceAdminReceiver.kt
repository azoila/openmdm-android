package com.openmdm.agent.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.openmdm.agent.R
import com.openmdm.agent.data.ProvisioningStore
import com.openmdm.agent.worker.WorkScheduler
import com.openmdm.library.enrollment.ManagedProvisioning
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
     * This is where the admin extras bundle finally arrives — the server URL, the
     * enrollment token, the policy id that the operator embedded in the QR code
     * or zero-touch configuration. **It is the only channel through which a
     * provisioned device learns which server it belongs to.**
     *
     * Before this override existed, the callback was not implemented at all: a
     * device provisioned by QR or zero-touch became Device Owner and then sat
     * there, extras dropped on the floor, waiting for someone to type an
     * enrollment code into it by hand. Provisioning looked supported and was not.
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

        val config = ManagedProvisioning.extractConfig(intent)

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

        ProvisioningStore(context).save(config)

        Log.i(TAG, "Provisioned for ${config.serverUrl}; scheduling enrollment")
        MdmTelemetryHolder.event(
            "provisioning_complete",
            mapOf(
                "has_server_url" to true,
                "has_enrollment_token" to (config.enrollmentToken != null),
                "has_policy_id" to (config.policyId != null),
            ),
        )

        WorkScheduler.enqueueProvisioningEnrollment(context)
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

    companion object {
        private const val TAG = "MDMDeviceAdmin"

        fun getComponentName(context: Context): android.content.ComponentName {
            return android.content.ComponentName(context, MDMDeviceAdminReceiver::class.java)
        }
    }
}
