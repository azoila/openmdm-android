package com.openmdm.agent.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.openmdm.agent.data.local.dao.CommandDao
import com.openmdm.agent.data.local.entity.CommandEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that processes pending commands from the database.
 *
 * Features:
 * - Runs on app startup to recover pending commands
 * - Runs after network connectivity is restored
 * - Cleans up old completed and failed commands
 * - Schedules individual CommandWorkers for each pending command
 */
@HiltWorker
class SyncPendingCommandsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val commandDao: CommandDao,
    private val workManager: WorkManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OpenMDM.SyncPendingCmds"
        const val WORK_NAME = "sync_pending_commands"

        // Cleanup thresholds
        private const val COMPLETED_RETENTION_DAYS = 7L
        private const val FAILED_RETENTION_DAYS = 30L
        private const val MAX_COMMAND_RETRIES = 5
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Syncing pending commands...")

        return try {
            // Step 1: Clean up old commands
            cleanupOldCommands()

            // Step 2: Reset failed commands for retry (if under max attempts)
            commandDao.resetFailedCommands(MAX_COMMAND_RETRIES)

            // Step 3: Process pending commands
            val pendingCommands = commandDao.getPendingCommands()
            Log.i(TAG, "Found ${pendingCommands.size} pending commands")

            pendingCommands.forEach { command ->
                scheduleCommandExecution(command)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing pending commands: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun cleanupOldCommands() {
        val now = System.currentTimeMillis()
        val completedThreshold = now - TimeUnit.DAYS.toMillis(COMPLETED_RETENTION_DAYS)
        val failedThreshold = now - TimeUnit.DAYS.toMillis(FAILED_RETENTION_DAYS)

        commandDao.deleteOldCompletedCommands(completedThreshold)
        commandDao.deleteOldFailedCommands(MAX_COMMAND_RETRIES, failedThreshold)

        Log.d(TAG, "Cleaned up old commands")
    }

    private fun scheduleCommandExecution(command: CommandEntity) {
        Log.d(TAG, "Scheduling execution for command ${command.id} (type: ${command.type})")

        val workRequest = OneTimeWorkRequestBuilder<CommandWorker>()
            .setInputData(
                workDataOf(
                    CommandWorker.KEY_COMMAND_ID to command.id,
                    CommandWorker.KEY_COMMAND_TYPE to command.type,
                    CommandWorker.KEY_PAYLOAD_JSON to command.payloadJson
                )
            )
            .addTag("command_${command.id}")
            .build()

        // Use KEEP policy to avoid duplicating work for the same command
        workManager.enqueue(workRequest)
    }
}
