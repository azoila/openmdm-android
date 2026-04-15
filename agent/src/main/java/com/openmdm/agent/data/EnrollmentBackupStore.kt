package com.openmdm.agent.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openmdm.agent.telemetry.AgentTelemetryHolder

/**
 * Secondary, write-through backup for enrollment state.
 *
 * ## Motivation
 *
 * The agent's primary enrollment store is a Jetpack DataStore file at
 * `mdm_settings`. On low-cost kiosk hardware (ZK-R32D and similar) an
 * unclean power cycle can leave that single file corrupted or zero-length.
 * When the agent comes up and DataStore returns nothing, the enrollment
 * flow concludes the device is unenrolled and starts over — producing the
 * "auto-unenroll" pattern we used to see in production.
 *
 * This class mirrors the minimum fields required to recover enrollment
 * into a SEPARATE, encrypted SharedPreferences file under the AndroidX
 * security-crypto library:
 *
 *  - **Separate file** from DataStore, so a single-file corruption does
 *    not lose both copies.
 *  - **Encrypted at rest** via [MasterKey] + AES-256 GCM keysets.
 *  - **Write-through**: every successful [MDMRepository.saveEnrollment] /
 *    [MDMRepository.updateToken] mirrors here; [MDMRepository.clearEnrollment]
 *    clears it too.
 *
 * On [MDMRepository.getEnrollmentState], if the primary DataStore looks
 * empty but this backup has data, the repository restores from here
 * and fires an `enrollment_state_restored_from_backup` event — one of
 * these in the field is an instant diagnosis.
 *
 * ## What this does NOT protect against
 *
 * A full app-data wipe (`pm clear com.openmdm.agent`, factory reset, OS
 * storage-pressure eviction of the whole app sandbox) destroys both
 * DataStore AND this backup in the same operation. Recovery from that
 * class of failure requires a server round-trip using hardware identity —
 * that's the Phase 2b device-pinned-key enrollment work in
 * `@openmdm/core@^0.9.0`. This layer closes the gap on single-file
 * corruption, not the full-wipe gap.
 *
 * ## Initialization failure
 *
 * If the AndroidX `security-crypto` provider can't initialize on this
 * device (rare but it happens on rooted or corrupted ROMs), construction
 * falls through to no-op mode rather than throwing. The primary
 * DataStore still works; we just lose the redundancy. A `nonFatal`
 * telemetry event is emitted so the failure is visible to operators.
 */
class EnrollmentBackupStore(context: Context) {
    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        Log.e(TAG, "EnrollmentBackupStore init failed — running without backup", t)
        AgentTelemetryHolder.nonFatal(t, context = "enrollment_backup_store_init")
        null
    }

    /** Mirror a full enrollment save into the backup. */
    fun save(
        deviceId: String,
        enrollmentId: String,
        token: String,
        refreshToken: String?,
        serverUrl: String,
        policyVersion: String?,
    ) {
        val p = prefs ?: return
        try {
            p.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_ENROLLMENT_ID, enrollmentId)
                .putString(KEY_TOKEN, token)
                .apply { if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken) }
                .putString(KEY_SERVER_URL, serverUrl)
                .apply { if (policyVersion != null) putString(KEY_POLICY_VERSION, policyVersion) }
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            AgentTelemetryHolder.nonFatal(t, context = "enrollment_backup_store_save")
        }
    }

    /** Mirror a token rotation into the backup. */
    fun updateToken(token: String, refreshToken: String?) {
        val p = prefs ?: return
        try {
            p.edit()
                .putString(KEY_TOKEN, token)
                .apply { if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken) }
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            AgentTelemetryHolder.nonFatal(t, context = "enrollment_backup_store_update_token")
        }
    }

    /**
     * Read the backup. Returns `null` when the backup is empty (no
     * saved enrollment) or when the store is in no-op mode.
     */
    fun load(): Snapshot? {
        val p = prefs ?: return null
        return try {
            val deviceId = p.getString(KEY_DEVICE_ID, null) ?: return null
            val token = p.getString(KEY_TOKEN, null) ?: return null
            Snapshot(
                deviceId = deviceId,
                enrollmentId = p.getString(KEY_ENROLLMENT_ID, null) ?: deviceId,
                token = token,
                refreshToken = p.getString(KEY_REFRESH_TOKEN, null),
                serverUrl = p.getString(KEY_SERVER_URL, null),
                policyVersion = p.getString(KEY_POLICY_VERSION, null),
                savedAt = p.getLong(KEY_SAVED_AT, 0L),
            )
        } catch (t: Throwable) {
            AgentTelemetryHolder.nonFatal(t, context = "enrollment_backup_store_load")
            null
        }
    }

    /** Clear the backup. Called by [MDMRepository.clearEnrollment]. */
    fun clear() {
        val p = prefs ?: return
        try {
            p.edit().clear().apply()
        } catch (t: Throwable) {
            AgentTelemetryHolder.nonFatal(t, context = "enrollment_backup_store_clear")
        }
    }

    /** Frozen view of what the backup held at load time. */
    data class Snapshot(
        val deviceId: String,
        val enrollmentId: String,
        val token: String,
        val refreshToken: String?,
        val serverUrl: String?,
        val policyVersion: String?,
        val savedAt: Long,
    )

    companion object {
        private const val TAG = "EnrollmentBackupStore"
        private const val FILE_NAME = "mdm_enrollment_backup"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ENROLLMENT_ID = "enrollment_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_POLICY_VERSION = "policy_version"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
