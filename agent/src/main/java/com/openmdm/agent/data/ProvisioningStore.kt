package com.openmdm.agent.data

import android.content.Context
import androidx.core.content.edit
import com.openmdm.library.enrollment.EnrollmentConfig

/**
 * What the device learned about its server during provisioning.
 *
 * A device provisioned by QR, NFC, `afw#` or zero-touch is told which server it
 * belongs to in the admin extras bundle — that is the *only* channel through
 * which it learns this. The agent's compiled-in `BuildConfig.MDM_SERVER_URL` is
 * a build-time default, useful for a fleet that forks and rebuilds the agent, and
 * useless for one that provisions a stock APK against its own server.
 *
 * So: whatever provisioning told us wins, and the build-time default is the
 * fallback. Written from a broadcast receiver, hence plain SharedPreferences —
 * a DataStore write would have to be async, and the receiver's window is short.
 */
class ProvisioningStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Persist what provisioning handed us. Silently ignores a config with no server. */
    fun save(config: EnrollmentConfig) {
        val serverUrl = config.serverUrl ?: return

        prefs.edit {
            putString(KEY_SERVER_URL, serverUrl)
            config.deviceSecret?.let { putString(KEY_DEVICE_SECRET, it) }
            config.enrollmentToken?.let { putString(KEY_ENROLLMENT_TOKEN, it) }
            config.policyId?.let { putString(KEY_POLICY_ID, it) }
            config.groupId?.let { putString(KEY_GROUP_ID, it) }
        }
    }

    /** The server this device was provisioned against, or null if it was not. */
    val serverUrl: String? get() = prefs.getString(KEY_SERVER_URL, null)

    /** A fleet secret supplied at provisioning time, overriding the build-time one. */
    val deviceSecret: String? get() = prefs.getString(KEY_DEVICE_SECRET, null)

    /** The enrollment/pairing token from the QR payload, if the operator embedded one. */
    val enrollmentToken: String? get() = prefs.getString(KEY_ENROLLMENT_TOKEN, null)

    val policyId: String? get() = prefs.getString(KEY_POLICY_ID, null)

    val groupId: String? get() = prefs.getString(KEY_GROUP_ID, null)

    /** True when this device was provisioned with a server to talk to. */
    val isProvisioned: Boolean get() = serverUrl != null

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val FILE_NAME = "openmdm_provisioning"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_SECRET = "device_secret"
        const val KEY_ENROLLMENT_TOKEN = "enrollment_token"
        const val KEY_POLICY_ID = "policy_id"
        const val KEY_GROUP_ID = "group_id"
    }
}
