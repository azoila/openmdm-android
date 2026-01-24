package com.openmdm.agent.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.openmdm.agent.data.*
import com.openmdm.agent.service.MDMService
import com.openmdm.agent.util.DeviceInfoCollector
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * WorkManager worker for periodic heartbeat sending.
 *
 * Features:
 * - Runs periodically (default 15 minutes - WorkManager minimum)
 * - Survives process death and device reboot
 * - Automatic exponential backoff retry on failure
 * - Network constraint ensures heartbeat only when connected
 */
@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mdmApi: MDMApi,
    private val mdmRepository: MDMRepository,
    private val deviceInfoCollector: DeviceInfoCollector
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OpenMDM.HeartbeatWorker"
        const val WORK_NAME = "heartbeat_work"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting heartbeat work (attempt ${runAttemptCount + 1})")

        return try {
            sendHeartbeat()
        } catch (e: IOException) {
            Log.w(TAG, "Heartbeat failed due to network error: ${e.message}")
            // Retry with exponential backoff
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed with unexpected error: ${e.message}")
            // For non-network errors, still retry but log the error
            if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Log.e(TAG, "Heartbeat failed after max retries")
                Result.failure()
            }
        }
    }

    private suspend fun sendHeartbeat(): Result {
        val state = mdmRepository.getEnrollmentState()
        if (!state.isEnrolled || state.token == null || state.deviceId == null) {
            Log.d(TAG, "Device not enrolled, skipping heartbeat")
            return Result.success() // Not enrolled - no heartbeat needed
        }

        val deviceInfo = deviceInfoCollector.collectHeartbeatData()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        val request = HeartbeatRequest(
            deviceId = state.deviceId,
            timestamp = timestamp,
            batteryLevel = deviceInfo.batteryLevel,
            isCharging = deviceInfo.isCharging,
            batteryHealth = deviceInfo.batteryHealth,
            storageUsed = deviceInfo.storageUsed,
            storageTotal = deviceInfo.storageTotal,
            memoryUsed = deviceInfo.memoryUsed,
            memoryTotal = deviceInfo.memoryTotal,
            networkType = deviceInfo.networkType,
            networkName = deviceInfo.networkName,
            signalStrength = deviceInfo.signalStrength,
            ipAddress = deviceInfo.ipAddress,
            location = deviceInfo.location?.let {
                LocationData(it.latitude, it.longitude, it.accuracy)
            },
            installedApps = deviceInfo.installedApps.map {
                InstalledAppData(it.packageName, it.version, it.versionCode)
            },
            runningApps = deviceInfo.runningApps,
            isRooted = deviceInfo.isRooted,
            isEncrypted = deviceInfo.isEncrypted,
            screenLockEnabled = deviceInfo.screenLockEnabled,
            agentVersion = deviceInfo.agentVersion,
            policyVersion = state.policyVersion
        )

        val response = mdmApi.heartbeat("Bearer ${state.token}", request)

        return if (response.isSuccessful) {
            Log.i(TAG, "Heartbeat successful")
            mdmRepository.updateLastSync()

            response.body()?.let { body ->
                // Process pending commands via service
                body.pendingCommands?.forEach { command ->
                    processCommandViaService(command, state.token)
                }

                // Handle policy update
                body.policyUpdate?.let { policy ->
                    policy.version?.let { mdmRepository.updatePolicyVersion(it) }
                    // Policy application is handled by MDMService
                }
            }

            Result.success()
        } else if (response.code() == 401) {
            Log.w(TAG, "Token expired, attempting refresh")
            if (refreshToken()) {
                // Retry heartbeat with new token
                Result.retry()
            } else {
                Log.e(TAG, "Token refresh failed")
                Result.failure()
            }
        } else if (response.code() in 500..599) {
            Log.w(TAG, "Server error ${response.code()}, will retry")
            Result.retry()
        } else {
            Log.e(TAG, "Heartbeat failed with code ${response.code()}")
            Result.failure()
        }
    }

    private suspend fun refreshToken(): Boolean {
        val state = mdmRepository.getEnrollmentState()
        val refreshToken = state.refreshToken ?: return false

        return try {
            val response = mdmApi.refreshToken(RefreshTokenRequest(refreshToken))
            if (response.isSuccessful) {
                response.body()?.let { body ->
                    mdmRepository.updateToken(body.token, body.refreshToken)
                }
                true
            } else {
                // Refresh failed - device needs to re-enroll
                mdmRepository.clearEnrollment()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error: ${e.message}")
            false
        }
    }

    private fun processCommandViaService(command: CommandResponse, token: String) {
        // For commands received during heartbeat, delegate to MDMService
        // which handles command execution with proper context
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
}
