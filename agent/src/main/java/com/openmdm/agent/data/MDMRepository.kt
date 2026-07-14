package com.openmdm.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openmdm.agent.BuildConfig
import com.openmdm.agent.telemetry.AgentTelemetryHolder
import com.openmdm.library.enrollment.EnrollmentBackupStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.openmdm.agent.domain.repository.IEnrollmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mdm_settings")

/**
 * MDM enrollment and state data
 */
data class EnrollmentState(
    val isEnrolled: Boolean = false,
    val deviceId: String? = null,
    val enrollmentId: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
    val serverUrl: String = BuildConfig.MDM_SERVER_URL,
    val policyVersion: String? = null,
    val lastSync: Long? = null
)

/**
 * Repository for MDM state and preferences.
 *
 * Implements [IEnrollmentRepository] for clean architecture compatibility.
 *
 * Uses two stores in write-through mode:
 *  - **Primary**: Jetpack DataStore (`mdm_settings`), fast and consumed by flows.
 *  - **Backup**: [EnrollmentBackupStore] via EncryptedSharedPreferences, kept in
 *    sync so a DataStore-file corruption does not lose enrollment and trigger
 *    the auto-unenroll pattern on low-cost kiosk hardware.
 *
 * All writes go through both stores; [getEnrollmentState] prefers the primary
 * but falls back to the backup when DataStore returns an empty row. See
 * [EnrollmentBackupStore] for the motivation and threat model.
 */
@Singleton
class MDMRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : IEnrollmentRepository {

    private val backupStore: EnrollmentBackupStore by lazy { EnrollmentBackupStore(context) }

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ENROLLMENT_ID = stringPreferencesKey("enrollment_id")
        val TOKEN = stringPreferencesKey("token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val SERVER_URL = stringPreferencesKey("server_url")
        val POLICY_VERSION = stringPreferencesKey("policy_version")
        val POLICY_SETTINGS = stringPreferencesKey("policy_settings")
        val LAST_SYNC = stringPreferencesKey("last_sync")
        val PUSH_TOKEN = stringPreferencesKey("push_token")

        /** Consecutive heartbeat auth errors (401/404). Reset to 0 on successful heartbeat. */
        val CONSECUTIVE_AUTH_ERRORS = intPreferencesKey("consecutive_auth_errors")

        /** Timestamp (millis) of last auth error. For technician debugging. */
        val LAST_AUTH_ERROR_AT = longPreferencesKey("last_auth_error_at")
    }

    override val enrollmentState: Flow<EnrollmentState> = context.dataStore.data.map { preferences ->
        EnrollmentState(
            isEnrolled = preferences[Keys.DEVICE_ID] != null,
            deviceId = preferences[Keys.DEVICE_ID],
            enrollmentId = preferences[Keys.ENROLLMENT_ID],
            token = preferences[Keys.TOKEN],
            refreshToken = preferences[Keys.REFRESH_TOKEN],
            serverUrl = preferences[Keys.SERVER_URL] ?: BuildConfig.MDM_SERVER_URL,
            policyVersion = preferences[Keys.POLICY_VERSION],
            lastSync = preferences[Keys.LAST_SYNC]?.toLongOrNull()
        )
    }

    override suspend fun getEnrollmentState(): EnrollmentState {
        val primary = try {
            enrollmentState.first()
        } catch (t: Throwable) {
            // DataStore read failures are a candidate root cause for the
            // "device re-enrolls after reboot" pattern — surface them as
            // non-fatals with the exception attached.
            AgentTelemetryHolder.nonFatal(t, context = "datastore_read_failed")
            EnrollmentState()
        }

        // Happy path: primary is populated, we're done.
        if (primary.isEnrolled && primary.deviceId != null && primary.token != null) {
            AgentTelemetryHolder.event(
                "enrollment_state_loaded",
                mapOf(
                    "source" to "primary",
                    "device_id" to primary.deviceId,
                    "has_refresh_token" to (primary.refreshToken != null),
                    "has_policy_version" to (primary.policyVersion != null),
                ),
            )
            return primary
        }

        // Fallback: primary is empty. Before concluding "unenrolled" (and
        // triggering the pairing UI), consult the encrypted backup. If the
        // backup has a snapshot, this is the exact failure mode the backup
        // store was designed to catch — a single-file loss on the primary
        // store.
        val snapshot = backupStore.load()
        if (snapshot != null) {
            AgentTelemetryHolder.event(
                "enrollment_state_restored_from_backup",
                mapOf(
                    "device_id" to snapshot.deviceId,
                    "snapshot_age_ms" to (System.currentTimeMillis() - snapshot.savedAt),
                    "has_refresh_token" to (snapshot.refreshToken != null),
                ),
            )
            // Rehydrate the primary store so the flow-based consumers see
            // a normal state on their next read — the backup is a ratchet,
            // not a sticky alternate path.
            try {
                context.dataStore.edit { preferences ->
                    preferences[Keys.DEVICE_ID] = snapshot.deviceId
                    preferences[Keys.ENROLLMENT_ID] = snapshot.enrollmentId
                    preferences[Keys.TOKEN] = snapshot.token
                    snapshot.refreshToken?.let { preferences[Keys.REFRESH_TOKEN] = it }
                    preferences[Keys.SERVER_URL] = snapshot.serverUrl ?: BuildConfig.MDM_SERVER_URL
                    snapshot.policyVersion?.let { preferences[Keys.POLICY_VERSION] = it }
                }
            } catch (t: Throwable) {
                AgentTelemetryHolder.nonFatal(t, context = "datastore_rehydrate_failed")
            }
            return EnrollmentState(
                isEnrolled = true,
                deviceId = snapshot.deviceId,
                enrollmentId = snapshot.enrollmentId,
                token = snapshot.token,
                refreshToken = snapshot.refreshToken,
                serverUrl = snapshot.serverUrl ?: BuildConfig.MDM_SERVER_URL,
                policyVersion = snapshot.policyVersion,
                lastSync = null,
            )
        }

        // Zombie risk: no primary, no backup. If this fires at boot, the
        // agent really has no idea who it is and is about to show the
        // pairing UI (or re-enroll).
        AgentTelemetryHolder.event(
            "enrollment_state_missing",
            mapOf(
                "has_device_id" to (primary.deviceId != null),
                "has_token" to (primary.token != null),
            ),
        )
        return primary
    }

    override suspend fun saveEnrollment(
        deviceId: String,
        enrollmentId: String,
        token: String,
        refreshToken: String?,
        serverUrl: String,
        policyVersion: String?
    ) {
        context.dataStore.edit { preferences ->
            preferences[Keys.DEVICE_ID] = deviceId
            preferences[Keys.ENROLLMENT_ID] = enrollmentId
            preferences[Keys.TOKEN] = token
            refreshToken?.let { preferences[Keys.REFRESH_TOKEN] = it }
            preferences[Keys.SERVER_URL] = serverUrl
            policyVersion?.let { preferences[Keys.POLICY_VERSION] = it }
            preferences[Keys.LAST_SYNC] = System.currentTimeMillis().toString()
        }
        // Write-through to the encrypted backup so recovery after a
        // primary-store corruption is possible without re-enrollment.
        backupStore.save(deviceId, enrollmentId, token, refreshToken, serverUrl, policyVersion)
        AgentTelemetryHolder.event(
            "enrollment_state_persisted",
            mapOf(
                "device_id" to deviceId,
                "enrollment_id" to enrollmentId,
                "has_refresh_token" to (refreshToken != null),
            ),
        )
    }

    override suspend fun updateToken(token: String, refreshToken: String?) {
        context.dataStore.edit { preferences ->
            preferences[Keys.TOKEN] = token
            refreshToken?.let { preferences[Keys.REFRESH_TOKEN] = it }
        }
        backupStore.updateToken(token, refreshToken)
    }

    override suspend fun updateLastSync() {
        context.dataStore.edit { preferences ->
            preferences[Keys.LAST_SYNC] = System.currentTimeMillis().toString()
        }
    }

    override suspend fun updatePolicyVersion(version: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.POLICY_VERSION] = version
        }
    }

    override suspend fun clearEnrollment() {
        // DESTRUCTIVE: records every call with a stack trace so we can always
        // answer "who wiped enrollment". After the reliability work landed,
        // this should only fire from a server-signed unenroll command — any
        // other caller is a bug.
        AgentTelemetryHolder.event(
            "enrollment_state_cleared",
            mapOf("caller_stack" to Throwable().stackTraceToString().take(600)),
        )
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.DEVICE_ID)
            preferences.remove(Keys.ENROLLMENT_ID)
            preferences.remove(Keys.TOKEN)
            preferences.remove(Keys.REFRESH_TOKEN)
            preferences.remove(Keys.POLICY_VERSION)
            preferences.remove(Keys.POLICY_SETTINGS)
            preferences.remove(Keys.LAST_SYNC)
        }
        // Clear the backup too — otherwise the next getEnrollmentState call
        // would rehydrate from it and effectively undo the unenroll.
        backupStore.clear()
    }

    /**
     * Save policy settings as JSON string.
     */
    suspend fun savePolicySettings(settingsJson: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.POLICY_SETTINGS] = settingsJson
        }
    }

    /**
     * Get saved policy settings JSON.
     */
    suspend fun getPolicySettingsJson(): String? {
        return context.dataStore.data.first()[Keys.POLICY_SETTINGS]
    }

    /**
     * Save FCM push token to avoid re-registration.
     */
    suspend fun savePushToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.PUSH_TOKEN] = token
        }
    }

    /**
     * Get saved push token.
     */
    suspend fun getPushToken(): String? {
        return context.dataStore.data.first()[Keys.PUSH_TOKEN]
    }

    // ============================================
    // Auth Error Tracking (observability)
    // ============================================

    /**
     * Increment consecutive auth error counter.
     * Called on 401/404 heartbeat responses for technician debugging.
     */
    suspend fun incrementConsecutiveAuthErrors() {
        context.dataStore.edit { preferences ->
            val current = preferences[Keys.CONSECUTIVE_AUTH_ERRORS] ?: 0
            preferences[Keys.CONSECUTIVE_AUTH_ERRORS] = current + 1
            preferences[Keys.LAST_AUTH_ERROR_AT] = System.currentTimeMillis()
        }
    }

    /**
     * Reset consecutive auth error counter on successful heartbeat.
     */
    suspend fun resetConsecutiveAuthErrors() {
        context.dataStore.edit { preferences ->
            preferences[Keys.CONSECUTIVE_AUTH_ERRORS] = 0
        }
    }

    /**
     * Get consecutive auth error count (for UI warning display).
     */
    suspend fun getConsecutiveAuthErrors(): Int {
        return context.dataStore.data.first()[Keys.CONSECUTIVE_AUTH_ERRORS] ?: 0
    }
}
