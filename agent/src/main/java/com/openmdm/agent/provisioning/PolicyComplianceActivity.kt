package com.openmdm.agent.provisioning

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.openmdm.library.telemetry.MdmTelemetryHolder

/**
 * The DPC's last word in the provisioning flow.
 *
 * **Mandatory on API 31+.** After the platform has made us Device Owner, it
 * launches this activity so the DPC can finish setting itself up and confirm it
 * is happy. Returning anything other than `RESULT_OK` fails the provisioning.
 *
 * The admin extras bundle rides on this intent, and this is the *earliest* point
 * at which the DPC is already Device Owner and holds it. So this is where the
 * device learns which server it belongs to and queues its enrollment — see
 * [ProvisioningHandoff] for why that no longer waits for the
 * `PROFILE_PROVISIONING_COMPLETE` broadcast alone.
 *
 * Enrollment itself is *not* run here. Handing off to WorkManager keeps this
 * activity to the few milliseconds the setup wizard is waiting on, and means a
 * device provisioned out of network range still enrolls when it finds a network.
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = ProvisioningHandoff.adopt(
            this,
            intent,
            ProvisioningHandoff.SOURCE_POLICY_COMPLIANCE,
        )

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
