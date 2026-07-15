package com.openmdm.agent.provisioning

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.openmdm.library.enrollment.ManagedProvisioning
import com.openmdm.library.enrollment.ProvisioningMode
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

        // Answer the mode the operator asked for in the QR / config, but only if
        // the platform allows it for how this device was launched into
        // provisioning — otherwise degrade rather than brick the setup wizard.
        // Defaults to fully-managed when unset, preserving historical behaviour.
        val requested = config?.provisioningMode ?: ProvisioningMode.FULLY_MANAGED_DEVICE
        val mode = ManagedProvisioning.resolveMode(intent, requested)

        Log.i(
            TAG,
            "Provisioning mode requested; answering $mode " +
                "(server=${config?.serverUrl ?: "not supplied"})",
        )
        MdmTelemetryHolder.event(
            "provisioning_mode_requested",
            mapOf("has_config" to (config != null), "mode" to mode.name),
        )

        setResult(RESULT_OK, ManagedProvisioning.provisioningModeResult(mode))
        finish()
    }

    private companion object {
        const val TAG = "ProvisioningMode"
    }
}
