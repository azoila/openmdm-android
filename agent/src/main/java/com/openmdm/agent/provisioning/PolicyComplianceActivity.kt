package com.openmdm.agent.provisioning

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.openmdm.library.enrollment.ManagedProvisioning
import com.openmdm.library.telemetry.MdmTelemetryHolder

/**
 * The DPC's last word in the provisioning flow.
 *
 * **Mandatory on API 31+.** After the platform has made us Device Owner, it
 * launches this activity so the DPC can finish setting itself up and confirm it
 * is happy. Returning anything other than `RESULT_OK` fails the provisioning.
 *
 * Enrollment is deliberately **not** driven from here. It is kicked off from
 * [com.openmdm.agent.receiver.MDMDeviceAdminReceiver.onProfileProvisioningComplete],
 * which fires whether or not this activity is shown, and which runs even if the
 * user backs out of the setup wizard's final screens. Making enrollment depend on
 * an activity the user can dismiss would mean a device that is Device Owner but
 * never talks to the server — managed, and unmanageable.
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = ManagedProvisioning.extractConfig(intent)

        Log.i(TAG, "Policy compliance screen; server=${config?.serverUrl ?: "not supplied"}")
        MdmTelemetryHolder.event(
            "provisioning_policy_compliance",
            mapOf("has_config" to (config != null)),
        )

        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val TAG = "PolicyCompliance"
    }
}
