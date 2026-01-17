package com.openmdm.library.api

/**
 * OpenMDM API Data Models
 *
 * These models match the OpenMDM server protocol.
 * Keep in sync with @openmdm/client TypeScript package.
 */

// ============================================
// Enrollment
// ============================================

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
    val signature: String,
    val policyId: String? = null,
    val groupId: String? = null
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

// ============================================
// Policy
// ============================================

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

// ============================================
// Heartbeat
// ============================================

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

// ============================================
// Commands
// ============================================

data class CommandResponse(
    val id: String,
    val type: String,
    val payload: Map<String, Any?>?,
    val status: String,
    val createdAt: String
)

data class CommandResultRequest(
    val success: Boolean,
    val message: String?,
    val data: Any?
)

data class CommandErrorRequest(
    val error: String
)

// ============================================
// Device Config
// ============================================

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

// ============================================
// Misc
// ============================================

data class PendingCommandsResponse(
    val commands: List<CommandResponse>
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
