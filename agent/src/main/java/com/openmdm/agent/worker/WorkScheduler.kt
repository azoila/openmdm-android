package com.openmdm.agent.worker

import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade for scheduling MDM background work via WorkManager.
 *
 * Provides a clean API for:
 * - Scheduling periodic heartbeats
 * - Scheduling one-time command execution
 * - Scheduling FCM token registration
 * - Syncing pending commands
 */
@Singleton
class WorkScheduler @Inject constructor(
    private val workManager: WorkManager
) {
    companion object {
        private const val TAG = "OpenMDM.WorkScheduler"

        // Default intervals
        const val DEFAULT_HEARTBEAT_INTERVAL_MINUTES = 15L // WorkManager minimum is 15 minutes
        const val MIN_HEARTBEAT_INTERVAL_MINUTES = 15L

        // Backoff policies
        private const val HEARTBEAT_BACKOFF_SECONDS = 10L
        private const val COMMAND_BACKOFF_SECONDS = 30L
        private const val PUSH_TOKEN_BACKOFF_SECONDS = 10L
    }

    /**
     * Schedule periodic heartbeat work.
     *
     * @param intervalMinutes Heartbeat interval in minutes (minimum 15)
     */
    fun scheduleHeartbeat(intervalMinutes: Long = DEFAULT_HEARTBEAT_INTERVAL_MINUTES) {
        val interval = intervalMinutes.coerceAtLeast(MIN_HEARTBEAT_INTERVAL_MINUTES)

        Log.i(TAG, "Scheduling periodic heartbeat every $interval minutes")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            interval, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                HEARTBEAT_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(HeartbeatWorker.WORK_NAME)
            .build()

        workManager.enqueueUniquePeriodicWork(
            HeartbeatWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            heartbeatRequest
        )
    }

    /**
     * Cancel periodic heartbeat.
     */
    fun cancelHeartbeat() {
        Log.i(TAG, "Cancelling periodic heartbeat")
        workManager.cancelUniqueWork(HeartbeatWorker.WORK_NAME)
    }

    /**
     * Trigger an immediate heartbeat (in addition to periodic).
     */
    fun triggerImmediateHeartbeat() {
        Log.d(TAG, "Triggering immediate heartbeat")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                HEARTBEAT_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag("immediate_heartbeat")
            .build()

        workManager.enqueue(immediateRequest)
    }

    /**
     * Schedule a command for execution.
     *
     * @param commandId Unique command ID
     * @param commandType Command type (e.g., "installApp")
     * @param payloadJson JSON-serialized command payload
     */
    fun scheduleCommand(commandId: String, commandType: String, payloadJson: String?) {
        Log.d(TAG, "Scheduling command $commandId (type: $commandType)")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val commandRequest = OneTimeWorkRequestBuilder<CommandWorker>()
            .setInputData(
                workDataOf(
                    CommandWorker.KEY_COMMAND_ID to commandId,
                    CommandWorker.KEY_COMMAND_TYPE to commandType,
                    CommandWorker.KEY_PAYLOAD_JSON to payloadJson
                )
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                COMMAND_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag("command_$commandId")
            .build()

        // Use unique work name to prevent duplicate processing
        workManager.enqueueUniqueWork(
            "${CommandWorker.WORK_NAME_PREFIX}$commandId",
            ExistingWorkPolicy.KEEP,
            commandRequest
        )
    }

    /**
     * Schedule FCM push token registration.
     *
     * @param fcmToken The FCM token to register
     */
    fun schedulePushTokenRegistration(fcmToken: String) {
        Log.d(TAG, "Scheduling push token registration")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val tokenRequest = OneTimeWorkRequestBuilder<PushTokenWorker>()
            .setInputData(
                workDataOf(PushTokenWorker.KEY_FCM_TOKEN to fcmToken)
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                PUSH_TOKEN_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(PushTokenWorker.WORK_NAME)
            .build()

        // Replace existing registration if any
        workManager.enqueueUniqueWork(
            PushTokenWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            tokenRequest
        )
    }

    /**
     * Sync and process any pending commands from the database.
     * Also cleans up old completed/failed commands.
     */
    fun syncPendingCommands() {
        Log.d(TAG, "Scheduling pending commands sync")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncPendingCommandsWorker>()
            .setConstraints(constraints)
            .addTag(SyncPendingCommandsWorker.WORK_NAME)
            .build()

        workManager.enqueueUniqueWork(
            SyncPendingCommandsWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    /**
     * Check if heartbeat is currently scheduled.
     */
    fun isHeartbeatScheduled(): Boolean {
        val workInfo = workManager.getWorkInfosForUniqueWork(HeartbeatWorker.WORK_NAME)
        return try {
            val infos = workInfo.get()
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Cancel all MDM work.
     */
    fun cancelAllWork() {
        Log.i(TAG, "Cancelling all MDM work")
        workManager.cancelAllWorkByTag(HeartbeatWorker.WORK_NAME)
        workManager.cancelAllWorkByTag(PushTokenWorker.WORK_NAME)
        workManager.cancelAllWorkByTag(SyncPendingCommandsWorker.WORK_NAME)
        // Note: Individual commands are cancelled by their unique names, not batch cancelled
    }

    /**
     * Get work status for debugging.
     */
    fun getWorkStatus(): Map<String, String> {
        val statuses = mutableMapOf<String, String>()

        try {
            val heartbeatInfo = workManager.getWorkInfosForUniqueWork(HeartbeatWorker.WORK_NAME).get()
            statuses["heartbeat"] = heartbeatInfo.firstOrNull()?.state?.name ?: "NOT_SCHEDULED"

            val tokenInfo = workManager.getWorkInfosForUniqueWork(PushTokenWorker.WORK_NAME).get()
            statuses["pushToken"] = tokenInfo.firstOrNull()?.state?.name ?: "NOT_SCHEDULED"

            val syncInfo = workManager.getWorkInfosForUniqueWork(SyncPendingCommandsWorker.WORK_NAME).get()
            statuses["syncCommands"] = syncInfo.firstOrNull()?.state?.name ?: "NOT_SCHEDULED"
        } catch (e: Exception) {
            statuses["error"] = e.message ?: "Unknown error"
        }

        return statuses
    }
}
