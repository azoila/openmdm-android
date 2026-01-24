package com.openmdm.agent.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.openmdm.agent.data.MDMApi
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.data.PushTokenRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

/**
 * WorkManager worker for FCM push token registration.
 *
 * Features:
 * - Maximum 10 retry attempts (token registration is critical)
 * - Exponential backoff on failure
 * - Survives process death - will retry after app restart
 */
@HiltWorker
class PushTokenWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mdmApi: MDMApi,
    private val mdmRepository: MDMRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OpenMDM.PushTokenWorker"
        const val WORK_NAME = "push_token_registration"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val MAX_RETRIES = 10
    }

    override suspend fun doWork(): Result {
        val fcmToken = inputData.getString(KEY_FCM_TOKEN)
        if (fcmToken.isNullOrEmpty()) {
            Log.e(TAG, "No FCM token provided")
            return Result.failure()
        }

        Log.d(TAG, "Registering push token (attempt ${runAttemptCount + 1})")

        return try {
            registerToken(fcmToken)
        } catch (e: IOException) {
            Log.w(TAG, "Token registration failed due to network error: ${e.message}")
            handleRetry()
        } catch (e: Exception) {
            Log.e(TAG, "Token registration failed with error: ${e.message}")
            handleRetry()
        }
    }

    private suspend fun registerToken(fcmToken: String): Result {
        val state = mdmRepository.getEnrollmentState()
        if (!state.isEnrolled || state.token == null) {
            Log.d(TAG, "Device not enrolled, cannot register push token")
            // Not enrolled yet - this will be retried when enrollment completes
            return Result.retry()
        }

        val response = mdmApi.registerPushToken(
            "Bearer ${state.token}",
            PushTokenRequest(provider = "fcm", token = fcmToken)
        )

        return if (response.isSuccessful) {
            Log.i(TAG, "Push token registered successfully")
            // Save the token so we don't re-register unnecessarily
            mdmRepository.savePushToken(fcmToken)
            Result.success()
        } else if (response.code() == 401) {
            Log.w(TAG, "Token expired during push token registration")
            // Token expired - retry after MDMService refreshes it
            handleRetry()
        } else if (response.code() in 500..599) {
            Log.w(TAG, "Server error ${response.code()}, will retry")
            handleRetry()
        } else {
            Log.e(TAG, "Push token registration failed with code ${response.code()}")
            // Client error (4xx except 401) - likely a permanent issue
            if (response.code() == 409) {
                // Token already registered - treat as success
                Log.i(TAG, "Push token already registered")
                Result.success()
            } else {
                Result.failure()
            }
        }
    }

    private fun handleRetry(): Result {
        return if (runAttemptCount < MAX_RETRIES) {
            Log.d(TAG, "Will retry token registration (attempt ${runAttemptCount + 1}/$MAX_RETRIES)")
            Result.retry()
        } else {
            Log.e(TAG, "Push token registration failed after $MAX_RETRIES attempts")
            Result.failure()
        }
    }
}
