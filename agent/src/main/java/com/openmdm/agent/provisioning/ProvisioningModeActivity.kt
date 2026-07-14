package com.openmdm.agent.provisioning

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.openmdm.library.enrollment.ManagedProvisioning
import com.openmdm.library.telemetry.MdmTelemetryHolder

/**
 * Answers the platform's `GET_PROVISIONING_MODE` question.
 *
 * **This activity is mandatory on API 31+.** During QR, NFC, `afw#` or
 * zero-touch provisioning, the setup wizard launches it to ask the DPC what kind
 * of management it wants. If the DPC does not declare it, the platform aborts the
 * provisioning — which is why QR provisioning could not work at all before this
 * existed, despite the agent shipping a complete QR *parser*.
 *
 * OpenMDM manages fully managed devices (kiosks, signage, dedicated hardware),
 * so the answer is always `PROVISIONING_MODE_FULLY_MANAGED_DEVICE`. There is no
 * UI: the activity answers and finishes immediately. A DPC that also offers work
 * profiles would show a chooser here.
 */
class ProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = ManagedProvisioning.extractConfig(intent)

        Log.i(
            TAG,
            "Provisioning mode requested; answering FULLY_MANAGED_DEVICE " +
                "(server=${config?.serverUrl ?: "not supplied"})",
        )
        MdmTelemetryHolder.event(
            "provisioning_mode_requested",
            mapOf("has_config" to (config != null)),
        )

        setResult(RESULT_OK, ManagedProvisioning.fullyManagedDeviceResult())
        finish()
    }

    private companion object {
        const val TAG = "ProvisioningMode"
    }
}
