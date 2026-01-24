package com.openmdm.agent.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.openmdm.agent.data.*
import com.openmdm.agent.data.local.dao.CommandDao
import com.openmdm.agent.data.local.entity.CommandEntity
import com.openmdm.agent.service.MDMService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

/**
 * WorkManager worker for executing individual MDM commands.
 *
 * Features:
 * - Persists command to Room database before execution
 * - Maximum 5 retry attempts with exponential backoff
 * - Survives process death - pending commands resume on restart
 * - Reports success/failure to server
 */
@HiltWorker
class CommandWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mdmApi: MDMApi,
    private val mdmRepository: MDMRepository,
    private val commandDao: CommandDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OpenMDM.CommandWorker"
        const val WORK_NAME_PREFIX = "command_"

        const val KEY_COMMAND_ID = "command_id"
        const val KEY_COMMAND_TYPE = "command_type"
        const val KEY_PAYLOAD_JSON = "payload_json"

        const val MAX_RETRIES = 5

        // Critical commands that should execute immediately without WorkManager
        val CRITICAL_COMMANDS = setOf("wipe", "lock", "factoryReset", "shutdown", "reboot")
    }

    override suspend fun doWork(): Result {
        val commandId = inputData.getString(KEY_COMMAND_ID) ?: return Result.failure()
        val commandType = inputData.getString(KEY_COMMAND_TYPE) ?: return Result.failure()
        val payloadJson = inputData.getString(KEY_PAYLOAD_JSON)

        Log.d(TAG, "Executing command $commandId (type: $commandType, attempt ${runAttemptCount + 1})")

        // Mark command as in progress
        commandDao.markInProgress(commandId)

        return try {
            executeCommand(commandId, commandType, payloadJson)
        } catch (e: IOException) {
            Log.w(TAG, "Command $commandId failed due to network error: ${e.message}")
            handleRetry(commandId, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Command $commandId failed with error: ${e.message}")
            handleRetry(commandId, e.message)
        }
    }

    private suspend fun executeCommand(
        commandId: String,
        commandType: String,
        payloadJson: String?
    ): Result {
        val state = mdmRepository.getEnrollmentState()
        if (!state.isEnrolled || state.token == null) {
            Log.w(TAG, "Device not enrolled, marking command as failed")
            commandDao.markFailed(commandId, "Device not enrolled")
            return Result.failure()
        }

        // Acknowledge receipt if not already done
        try {
            mdmApi.acknowledgeCommand("Bearer ${state.token}", commandId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acknowledge command (may already be acked): ${e.message}")
        }

        // Execute command via MDMService (which has full context for execution)
        val command = CommandResponse(
            id = commandId,
            type = commandType,
            payload = payloadJson?.let { parsePayload(it) },
            status = "pending",
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date())
        )

        // For non-critical commands, delegate to service
        delegateToService(command)

        // Mark as completed in our database (service will report to server)
        commandDao.markCompleted(commandId)

        Log.i(TAG, "Command $commandId execution delegated to service")
        return Result.success()
    }

    private fun parsePayload(json: String): Map<String, Any?>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(json, Map::class.java) as? Map<String, Any?>
        } catch (e: Exception) {
            null
        }
    }

    private fun delegateToService(command: CommandResponse) {
        val intent = Intent(applicationContext, MDMService::class.java).apply {
            action = MDMService.ACTION_PROCESS_COMMAND
            putExtra(MDMService.EXTRA_COMMAND, Gson().toJson(command))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }

    private suspend fun handleRetry(commandId: String, errorMessage: String?): Result {
        return if (runAttemptCount < MAX_RETRIES) {
            Log.d(TAG, "Will retry command $commandId (attempt ${runAttemptCount + 1}/$MAX_RETRIES)")
            // Update attempt count in database
            commandDao.updateCommandStatus(
                id = commandId,
                status = CommandEntity.STATUS_PENDING,
                timestamp = System.currentTimeMillis(),
                error = errorMessage
            )
            Result.retry()
        } else {
            Log.e(TAG, "Command $commandId failed after $MAX_RETRIES attempts")
            commandDao.markFailed(commandId, errorMessage)

            // Report failure to server
            try {
                val state = mdmRepository.getEnrollmentState()
                if (state.token != null) {
                    mdmApi.failCommand(
                        "Bearer ${state.token}",
                        commandId,
                        CommandErrorRequest(errorMessage ?: "Max retries exceeded")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report command failure to server: ${e.message}")
            }

            Result.failure(
                workDataOf(
                    "command_id" to commandId,
                    "error" to (errorMessage ?: "Max retries exceeded")
                )
            )
        }
    }
}
