package com.openmdm.agent.data

import retrofit2.Response
import retrofit2.http.*

/**
 * MDM Server API interface
 */
interface MDMApi {

    /**
     * Phase 2b: fetch a single-use enrollment challenge.
     *
     * The server returns a fresh nonce that the agent must:
     *  1. Include in the canonical enrollment message it signs.
     *  2. Echo back in the `attestationChallenge` field of the
     *     subsequent [enroll] request.
     *
     * Challenges are atomically consumed on first use — a second
     * enrollment request reusing the same challenge is rejected
     * with a `ChallengeInvalidError`. Default TTL is 5 minutes.
     *
     * Unauthenticated by design: the agent has no credentials yet
     * at this point in the flow, and a challenge is only useful
     * combined with a valid ECDSA signature nobody can produce
     * without the device's Keystore-held private key.
     *
     * The server returns 503 if challenge storage is not configured
     * (e.g. running against an adapter without the enrollment
     * challenge table). The agent should treat 503 as "pinned-key
     * enrollment is not supported by this server version, fall
     * back to HMAC" rather than as a retry target.
     */
    @GET("agent/enroll/challenge")
    suspend fun fetchEnrollmentChallenge(): Response<EnrollmentChallengeResponse>

    @POST("agent/enroll")
    suspend fun enroll(@Body request: EnrollmentRequest): Response<EnrollmentResponse>

    @POST("agent/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") token: String,
        @Body request: HeartbeatRequest
    ): Response<HeartbeatResponse>

    @GET("agent/config")
    suspend fun getConfig(
        @Header("Authorization") token: String
    ): Response<DeviceConfigResponse>

    @GET("agent/commands/pending")
    suspend fun getPendingCommands(
        @Header("Authorization") token: String
    ): Response<PendingCommandsResponse>

    @POST("agent/commands/{commandId}/ack")
    suspend fun acknowledgeCommand(
        @Header("Authorization") token: String,
        @Path("commandId") commandId: String
    ): Response<Unit>

    @POST("agent/commands/{commandId}/complete")
    suspend fun completeCommand(
        @Header("Authorization") token: String,
        @Path("commandId") commandId: String,
        @Body result: CommandResultRequest
    ): Response<Unit>

    @POST("agent/commands/{commandId}/fail")
    suspend fun failCommand(
        @Header("Authorization") token: String,
        @Path("commandId") commandId: String,
        @Body error: CommandErrorRequest
    ): Response<Unit>

    @POST("agent/push-token")
    suspend fun registerPushToken(
        @Header("Authorization") token: String,
        @Body request: PushTokenRequest
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "agent/push-token", hasBody = true)
    suspend fun unregisterPushToken(
        @Header("Authorization") token: String,
        @Body request: PushTokenRequest
    ): Response<Unit>

    @POST("agent/events")
    suspend fun reportEvent(
        @Header("Authorization") token: String,
        @Body request: EventRequest
    ): Response<Unit>

    @POST("agent/refresh-token")
    suspend fun refreshToken(
        @Body request: RefreshTokenRequest
    ): Response<TokenResponse>
}

// Request/Response DTOs

data class EnrollmentRequest(
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val sdkVersion: Int,
    val serialNumber: String?,
    val imei: String?,
    val macAddress: String?,
    val androidId: String?,
    val agentVersion: String,
    val agentPackage: String,
    val method: String,
    val timestamp: String,
    /**
     * Phase 2a HMAC path: hex-encoded HMAC-SHA256 of the nine-field
     * canonical form (see `SignatureGenerator`).
     *
     * Phase 2b device-pinned-key path: base64-encoded DER ECDSA-P256
     * signature over the eleven-field canonical form (see
     * `CanonicalEnrollmentMessage`). The server distinguishes the
     * two paths by whether [publicKey] is present.
     */
    val signature: String,

    /**
     * Phase 2b: base64-encoded SPKI EC P-256 public key generated
     * in the device's Keystore. Null on the legacy HMAC path; the
     * server pins this key on the device row on first enroll and
     * rejects any future enrollment that tries a different key.
     */
    val publicKey: String? = null,

    /**
     * Phase 2b: echoed challenge from [MDMApi.fetchEnrollmentChallenge].
     * Required whenever [publicKey] is present. Null on the HMAC
     * path.
     */
    val attestationChallenge: String? = null,

    val policyId: String? = null,
    val groupId: String? = null
)

/**
 * Phase 2b: response from `GET /agent/enroll/challenge`. The
 * `challenge` string goes into the canonical signed message and
 * the [EnrollmentRequest.attestationChallenge] field.
 */
data class EnrollmentChallengeResponse(
    val challenge: String,
    val expiresAt: String,
    val ttlSeconds: Int
)

data class EnrollmentResponse(
    val deviceId: String,
    val enrollmentId: String,
    val policyId: String?,
    val policy: PolicyResponse?,
    val serverUrl: String,
    val pushConfig: PushConfigResponse,
    val token: String,
    val refreshToken: String?,
    val tokenExpiresAt: String?
)

data class PolicyResponse(
    val id: String,
    val name: String,
    val version: String?,
    val settings: Map<String, Any?>
)

data class PushConfigResponse(
    val provider: String,
    val fcmSenderId: String?,
    val mqttUrl: String?,
    val mqttTopic: String?,
    val pollingInterval: Int?
)

data class HeartbeatRequest(
    val deviceId: String,
    val timestamp: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val batteryHealth: String?,
    val storageUsed: Long,
    val storageTotal: Long,
    val memoryUsed: Long,
    val memoryTotal: Long,
    val networkType: String?,
    val networkName: String?,
    val signalStrength: Int?,
    val ipAddress: String?,
    val location: LocationData?,
    val installedApps: List<InstalledAppData>,
    val runningApps: List<String>?,
    val isRooted: Boolean?,
    val isEncrypted: Boolean?,
    val screenLockEnabled: Boolean?,
    val agentVersion: String,
    val policyVersion: String?
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?
)

data class InstalledAppData(
    val packageName: String,
    val version: String,
    val versionCode: Long?
)

data class HeartbeatResponse(
    val success: Boolean,
    val pendingCommands: List<CommandResponse>?,
    val policyUpdate: PolicyResponse?,
    val message: String?
)

data class CommandResponse(
    val id: String,
    val type: String,
    val payload: Map<String, Any?>?,
    val status: String,
    val createdAt: String
)

data class DeviceConfigResponse(
    val policy: PolicyResponse?,
    val applications: List<ApplicationResponse>?,
    val pendingCommands: List<CommandResponse>?
)

data class ApplicationResponse(
    val name: String,
    val packageName: String,
    val version: String,
    val url: String,
    val hash: String?,
    val showIcon: Boolean?,
    val runAfterInstall: Boolean?,
    val runAtBoot: Boolean?
)

data class PendingCommandsResponse(
    val commands: List<CommandResponse>
)

data class CommandResultRequest(
    val success: Boolean,
    val message: String?,
    val data: Any?
)

data class CommandErrorRequest(
    val error: String
)

data class PushTokenRequest(
    val provider: String,
    val token: String? = null
)

data class EventRequest(
    val type: String,
    val payload: Map<String, Any?>?,
    val timestamp: String
)

data class RefreshTokenRequest(
    val refreshToken: String
)

data class TokenResponse(
    val token: String,
    val refreshToken: String?,
    val expiresAt: String?
)
