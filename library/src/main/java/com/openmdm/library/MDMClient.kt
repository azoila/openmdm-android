package com.openmdm.library

import com.openmdm.library.api.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OpenMDM Client
 *
 * High-level client for interacting with OpenMDM server.
 * Handles authentication, HMAC signing, and API calls.
 *
 * Usage:
 * ```kotlin
 * val client = MDMClient.Builder()
 *     .serverUrl("https://mdm.example.com")
 *     .deviceSecret("your-shared-secret")
 *     .build()
 *
 * // Enroll device
 * val response = client.enroll(enrollmentRequest)
 *
 * // Send heartbeat
 * client.heartbeat(heartbeatData)
 * ```
 */
class MDMClient private constructor(
    private val serverUrl: String,
    private val deviceSecret: String,
    private val api: MDMApi,
    private val onTokenRefresh: ((String, String?) -> Unit)?,
    private val onEnrollmentLost: (() -> Unit)?
) {
    private var token: String? = null
    private var refreshToken: String? = null
    private var deviceId: String? = null

    class Builder {
        private var serverUrl: String = ""
        private var deviceSecret: String = ""
        private var timeout: Long = 30
        private var debug: Boolean = false
        private var onTokenRefresh: ((String, String?) -> Unit)? = null
        private var onEnrollmentLost: (() -> Unit)? = null

        fun serverUrl(url: String) = apply { this.serverUrl = url.trimEnd('/') }
        fun deviceSecret(secret: String) = apply { this.deviceSecret = secret }
        fun timeout(seconds: Long) = apply { this.timeout = seconds }
        fun debug(enabled: Boolean) = apply { this.debug = enabled }
        fun onTokenRefresh(callback: (String, String?) -> Unit) = apply { this.onTokenRefresh = callback }
        fun onEnrollmentLost(callback: () -> Unit) = apply { this.onEnrollmentLost = callback }

        fun build(): MDMClient {
            require(serverUrl.isNotBlank()) { "Server URL is required" }
            require(deviceSecret.isNotBlank()) { "Device secret is required" }

            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = if (debug) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(serverUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val api = retrofit.create(MDMApi::class.java)

            return MDMClient(serverUrl, deviceSecret, api, onTokenRefresh, onEnrollmentLost)
        }
    }

    // ============================================
    // State Management
    // ============================================

    fun isEnrolled(): Boolean = token != null && deviceId != null

    fun setCredentials(deviceId: String, token: String, refreshToken: String? = null) {
        this.deviceId = deviceId
        this.token = token
        this.refreshToken = refreshToken
    }

    fun clearCredentials() {
        this.deviceId = null
        this.token = null
        this.refreshToken = null
    }

    fun getDeviceId(): String? = deviceId
    fun getToken(): String? = token

    // ============================================
    // Enrollment
    // ============================================

    suspend fun enroll(request: EnrollmentRequest): Result<EnrollmentResponse> {
        return try {
            val response = api.enroll(request)
            if (response.isSuccessful) {
                val body = response.body()!!
                setCredentials(body.deviceId, body.token, body.refreshToken)
                onTokenRefresh?.invoke(body.token, body.refreshToken)
                Result.success(body)
            } else {
                Result.failure(Exception("Enrollment failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate enrollment signature using HMAC-SHA256
     */
    fun generateEnrollmentSignature(
        model: String,
        manufacturer: String,
        osVersion: String,
        serialNumber: String?,
        imei: String?,
        macAddress: String?,
        androidId: String?,
        method: String,
        timestamp: String
    ): String {
        val message = listOf(
            model,
            manufacturer,
            osVersion,
            serialNumber ?: "",
            imei ?: "",
            macAddress ?: "",
            androidId ?: "",
            method,
            timestamp
        ).joinToString("|")

        return hmacSha256(message, deviceSecret)
    }

    private fun hmacSha256(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ============================================
    // Heartbeat
    // ============================================

    suspend fun heartbeat(request: HeartbeatRequest): Result<HeartbeatResponse> {
        val authToken = token ?: return Result.failure(Exception("Not enrolled"))

        return try {
            val response = api.heartbeat("Bearer $authToken", request)
            when {
                response.isSuccessful -> Result.success(response.body()!!)
                response.code() == 401 -> {
                    // Try to refresh token
                    if (tryRefreshToken()) {
                        heartbeat(request) // Retry with new token
                    } else {
                        onEnrollmentLost?.invoke()
                        Result.failure(Exception("Authentication failed"))
                    }
                }
                else -> Result.failure(Exception("Heartbeat failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // Commands
    // ============================================

    suspend fun getPendingCommands(): Result<List<CommandResponse>> {
        val authToken = token ?: return Result.failure(Exception("Not enrolled"))

        return try {
            val response = api.getPendingCommands("Bearer $authToken")
            if (response.isSuccessful) {
                Result.success(response.body()?.commands ?: emptyList())
            } else {
                Result.failure(Exception("Failed to get commands: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acknowledgeCommand(commandId: String): Result<Unit> {
        val authToken = token ?: return Result.failure(Exception("Not enrolled"))

        return try {
            val response = api.acknowledgeCommand("Bearer $authToken", commandId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to acknowledge: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeCommand(commandId: String, result: CommandResultRequest): Result<Unit> {
        val authToken = token ?: return Result.failure(Exception("Not enrolled"))

        return try {
            val response = api.completeCommand("Bearer $authToken", commandId, result)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to complete: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun failCommand(commandId: String, error: String): Result<Unit> {
        val authToken = token ?: return Result.failure(Exception("Not enrolled"))

        return try {
            val response = api.failCommand("Bearer $authToken", commandId, CommandErrorRequest(error))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to report error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // Push Tokens
    // ============================================

    suspend fun registerPushToken(provider: String, pushToken: String): Result<Unit> {
        val authToken = token ?: return Result.failure(Exception("Not enrolled"))

        return try {
            val response = api.registerPushToken(
                "Bearer $authToken",
                PushTokenRequest(provider, pushToken)
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to register token: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // Token Refresh
    // ============================================

    private suspend fun tryRefreshToken(): Boolean {
        val currentRefreshToken = refreshToken ?: return false

        return try {
            val response = api.refreshToken(RefreshTokenRequest(currentRefreshToken))
            if (response.isSuccessful) {
                val body = response.body()!!
                token = body.token
                body.refreshToken?.let { refreshToken = it }
                onTokenRefresh?.invoke(body.token, body.refreshToken)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
