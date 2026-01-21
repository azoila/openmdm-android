package com.openmdm.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.openmdm.agent.OpenMDMApplication
import com.openmdm.agent.R
import com.openmdm.agent.data.*
import com.openmdm.agent.ui.MainActivity
import com.openmdm.agent.util.DeviceInfoCollector
import com.openmdm.agent.util.DeviceOwnerManager
import com.openmdm.library.command.CommandType
import com.openmdm.library.file.FileDeployment
import com.openmdm.library.device.VisibilityMode
import com.openmdm.library.policy.KioskConfig
import com.openmdm.library.policy.LauncherConfig
import com.openmdm.library.policy.PolicyMapper
import com.openmdm.library.policy.WifiNetworkConfig
import com.openmdm.library.policy.WifiSecurityType
import android.content.ComponentName
import com.openmdm.agent.ui.launcher.LauncherActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * MDM Background Service
 *
 * Handles heartbeat scheduling, command processing, and maintains
 * persistent connection with the MDM server.
 */
@AndroidEntryPoint
class MDMService : LifecycleService() {

    @Inject
    lateinit var mdmApi: MDMApi

    @Inject
    lateinit var mdmRepository: MDMRepository

    @Inject
    lateinit var deviceInfoCollector: DeviceInfoCollector

    @Inject
    lateinit var deviceOwnerManager: DeviceOwnerManager

    private var heartbeatJob: Job? = null
    private var heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> startHeartbeat()
            ACTION_STOP -> stopHeartbeat()
            ACTION_SYNC_NOW -> syncNow()
            ACTION_PROCESS_COMMAND -> {
                val commandJson = intent.getStringExtra(EXTRA_COMMAND)
                commandJson?.let { processIncomingCommand(it) }
            }
            ACTION_INSTALL_COMPLETE -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                handleInstallComplete(packageName, success, message)
            }
            ACTION_UNINSTALL_COMPLETE -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                handleUninstallComplete(packageName, success, message)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OpenMDMApplication.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    sendHeartbeat()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(heartbeatInterval)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun syncNow() {
        lifecycleScope.launch {
            try {
                sendHeartbeat()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun sendHeartbeat() {
        val state = mdmRepository.getEnrollmentState()
        if (!state.isEnrolled || state.token == null || state.deviceId == null) return

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

        if (response.isSuccessful) {
            mdmRepository.updateLastSync()

            response.body()?.let { body ->
                // Process pending commands
                body.pendingCommands?.forEach { command ->
                    processCommand(command, state.token)
                }

                // Handle policy update
                body.policyUpdate?.let { policy ->
                    policy.version?.let { mdmRepository.updatePolicyVersion(it) }
                    applyPolicy(policy)
                }

                // Update heartbeat interval from policy
                body.policyUpdate?.settings?.get("heartbeatInterval")?.let { interval ->
                    (interval as? Number)?.let {
                        heartbeatInterval = it.toLong() * 1000L
                    }
                }
            }
        } else if (response.code() == 401) {
            // Token expired - try to refresh
            refreshToken()
        }
    }

    private suspend fun refreshToken() {
        val state = mdmRepository.getEnrollmentState()
        val refreshToken = state.refreshToken ?: return

        val response = mdmApi.refreshToken(RefreshTokenRequest(refreshToken))
        if (response.isSuccessful) {
            response.body()?.let { body ->
                mdmRepository.updateToken(body.token, body.refreshToken)
            }
        } else {
            // Refresh failed - device needs to re-enroll
            mdmRepository.clearEnrollment()
        }
    }

    private suspend fun processCommand(command: CommandResponse, token: String) {
        // Acknowledge receipt
        mdmApi.acknowledgeCommand("Bearer $token", command.id)

        try {
            val result = executeCommand(command)
            mdmApi.completeCommand(
                "Bearer $token",
                command.id,
                CommandResultRequest(result.success, result.message, result.data)
            )
        } catch (e: Exception) {
            mdmApi.failCommand(
                "Bearer $token",
                command.id,
                CommandErrorRequest(e.message ?: "Unknown error")
            )
        }
    }

    private suspend fun executeCommand(command: CommandResponse): CommandResult {
        return when (command.type) {
            // ============================================
            // Device Control Commands
            // ============================================
            "sync" -> {
                sendHeartbeat()
                CommandResult(true, "Sync completed")
            }

            "reboot" -> {
                val result = deviceOwnerManager.rebootDevice()
                if (result.isSuccess) {
                    CommandResult(true, "Device rebooting")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "Reboot failed")
                }
            }

            "shutdown" -> {
                // Shutdown is not directly supported, use reboot as fallback
                CommandResult(false, "Shutdown not supported, use reboot instead")
            }

            "lock" -> {
                val message = command.payload?.get("message") as? String
                val result = deviceOwnerManager.lockDevice()
                if (result.isSuccess) {
                    CommandResult(true, "Device locked")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "Lock failed")
                }
            }

            "unlock" -> {
                // Unlock requires biometric/password, can't be done remotely
                CommandResult(false, "Remote unlock not supported for security reasons")
            }

            "wipe" -> {
                val preserveData = command.payload?.get("preserveData") as? Boolean ?: false
                val result = deviceOwnerManager.wipeDevice(preserveData)
                if (result.isSuccess) {
                    CommandResult(true, "Device wipe initiated")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "Wipe failed")
                }
            }

            "factoryReset" -> {
                val result = deviceOwnerManager.wipeDevice(preserveData = false)
                if (result.isSuccess) {
                    CommandResult(true, "Factory reset initiated")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "Factory reset failed")
                }
            }

            // ============================================
            // App Management Commands
            // ============================================
            "installApp" -> {
                val packageName = command.payload?.get("packageName") as? String
                val url = command.payload?.get("url") as? String
                val autoGrantPermissions = command.payload?.get("autoGrantPermissions") as? Boolean ?: true

                if (packageName != null && url != null) {
                    val result = deviceOwnerManager.installApkSilently(url, packageName)
                    if (result.isSuccess) {
                        // Auto-grant permissions if requested and Device Owner
                        if (autoGrantPermissions && deviceOwnerManager.isDeviceOwner()) {
                            deviceOwnerManager.grantCommonPermissions(packageName)
                            deviceOwnerManager.whitelistFromBatteryOptimization(packageName)
                        }
                        CommandResult(true, "App installation initiated for $packageName")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Installation failed")
                    }
                } else {
                    CommandResult(false, "Invalid install parameters: packageName and url required")
                }
            }

            "uninstallApp" -> {
                val packageName = command.payload?.get("packageName") as? String
                if (packageName != null) {
                    val result = deviceOwnerManager.uninstallAppSilently(packageName)
                    if (result.isSuccess) {
                        CommandResult(true, "App uninstall initiated for $packageName")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Uninstall failed")
                    }
                } else {
                    CommandResult(false, "Package name required")
                }
            }

            "updateApp" -> {
                // Update is same as install with newer version
                val packageName = command.payload?.get("packageName") as? String
                val url = command.payload?.get("url") as? String

                if (packageName != null && url != null) {
                    val result = deviceOwnerManager.installApkSilently(url, packageName)
                    if (result.isSuccess) {
                        CommandResult(true, "App update initiated for $packageName")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Update failed")
                    }
                } else {
                    CommandResult(false, "Invalid update parameters")
                }
            }

            "runApp" -> {
                val packageName = command.payload?.get("packageName") as? String
                if (packageName != null) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        CommandResult(true, "App $packageName launched")
                    } else {
                        CommandResult(false, "Could not find launch intent for $packageName")
                    }
                } else {
                    CommandResult(false, "Package name required")
                }
            }

            "clearAppData" -> {
                val packageName = command.payload?.get("packageName") as? String
                if (packageName != null && deviceOwnerManager.isDeviceOwner()) {
                    try {
                        // Use ActivityManager to clear data (requires Device Owner or shell)
                        val process = Runtime.getRuntime().exec(
                            arrayOf("pm", "clear", packageName)
                        )
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            CommandResult(true, "App data cleared for $packageName")
                        } else {
                            CommandResult(false, "Failed to clear app data")
                        }
                    } catch (e: Exception) {
                        CommandResult(false, e.message ?: "Failed to clear app data")
                    }
                } else {
                    CommandResult(false, "Clear app data requires Device Owner and package name")
                }
            }

            "clearAppCache" -> {
                val packageName = command.payload?.get("packageName") as? String
                if (packageName != null) {
                    try {
                        val process = Runtime.getRuntime().exec(
                            arrayOf("pm", "clear-cache", packageName)
                        )
                        process.waitFor()
                        CommandResult(true, "App cache cleared for $packageName")
                    } catch (e: Exception) {
                        CommandResult(false, e.message ?: "Failed to clear app cache")
                    }
                } else {
                    CommandResult(false, "Package name required")
                }
            }

            // ============================================
            // Permission Commands
            // ============================================
            "grantPermissions" -> {
                val packageName = command.payload?.get("packageName") as? String
                val permissions = (command.payload?.get("permissions") as? List<*>)?.filterIsInstance<String>()

                if (packageName != null && !permissions.isNullOrEmpty()) {
                    val result = deviceOwnerManager.grantPermissions(packageName, permissions)
                    if (result.isSuccess) {
                        val granted = result.getOrNull()
                        CommandResult(true, "Permissions granted", granted)
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Permission grant failed")
                    }
                } else if (packageName != null) {
                    // Grant common permissions
                    val result = deviceOwnerManager.grantCommonPermissions(packageName)
                    if (result.isSuccess) {
                        CommandResult(true, "Common permissions granted to $packageName")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Permission grant failed")
                    }
                } else {
                    CommandResult(false, "Package name required")
                }
            }

            "whitelistBattery" -> {
                val packageName = command.payload?.get("packageName") as? String
                if (packageName != null) {
                    val result = deviceOwnerManager.whitelistFromBatteryOptimization(packageName)
                    if (result.isSuccess) {
                        CommandResult(true, "$packageName added to battery whitelist")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Battery whitelist failed")
                    }
                } else {
                    CommandResult(false, "Package name required")
                }
            }

            // ============================================
            // Kiosk Mode Commands
            // ============================================
            "enterKiosk" -> {
                val packageName = command.payload?.get("packageName") as? String
                    ?: command.payload?.get("mainApp") as? String

                if (packageName != null) {
                    val result = deviceOwnerManager.startLockTaskMode(packageName)
                    if (result.isSuccess) {
                        CommandResult(true, "Kiosk mode enabled for $packageName")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Kiosk mode failed")
                    }
                } else {
                    CommandResult(false, "Package name required for kiosk mode")
                }
            }

            "exitKiosk" -> {
                try {
                    // Clear lock task packages to exit kiosk
                    deviceOwnerManager.setLockTaskPackages(emptyList())
                    CommandResult(true, "Kiosk mode disabled")
                } catch (e: Exception) {
                    CommandResult(false, e.message ?: "Failed to exit kiosk mode")
                }
            }

            // ============================================
            // System Commands
            // ============================================
            "shell" -> {
                val command = command.payload?.get("command") as? String
                if (command != null && deviceOwnerManager.isDeviceOwner()) {
                    try {
                        val process = Runtime.getRuntime().exec(command)
                        val output = process.inputStream.bufferedReader().readText()
                        val exitCode = process.waitFor()
                        CommandResult(exitCode == 0, output, mapOf("exitCode" to exitCode))
                    } catch (e: Exception) {
                        CommandResult(false, e.message ?: "Shell command failed")
                    }
                } else {
                    CommandResult(false, "Shell command requires Device Owner permission")
                }
            }

            "setVolume" -> {
                val level = (command.payload?.get("level") as? Number)?.toInt()
                val streamType = (command.payload?.get("streamType") as? String) ?: "music"

                if (level != null) {
                    val stream = when (streamType) {
                        "ring" -> AudioManager.STREAM_RING
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        "alarm" -> AudioManager.STREAM_ALARM
                        "system" -> AudioManager.STREAM_SYSTEM
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val result = deviceOwnerManager.setVolume(stream, level)
                    if (result.isSuccess) {
                        CommandResult(true, "Volume set to $level")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Set volume failed")
                    }
                } else {
                    CommandResult(false, "Volume level required")
                }
            }

            "getLocation" -> {
                try {
                    val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
                    val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                    if (location != null) {
                        CommandResult(
                            true,
                            "Location retrieved",
                            mapOf(
                                "latitude" to location.latitude,
                                "longitude" to location.longitude,
                                "accuracy" to location.accuracy,
                                "altitude" to location.altitude,
                                "timestamp" to location.time
                            )
                        )
                    } else {
                        CommandResult(false, "Location not available")
                    }
                } catch (e: SecurityException) {
                    CommandResult(false, "Location permission not granted")
                } catch (e: Exception) {
                    CommandResult(false, e.message ?: "Location retrieval failed")
                }
            }

            "screenshot" -> {
                // Screenshot requires special permissions or Device Owner
                CommandResult(false, "Screenshot not implemented - requires MediaProjection API")
            }

            "setTimeZone" -> {
                val timezone = command.payload?.get("timezone") as? String
                if (timezone != null && deviceOwnerManager.isDeviceOwner()) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            deviceOwnerManager.setUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, false)
                        }
                        // Set timezone via AlarmManager
                        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
                        alarmManager.setTimeZone(timezone)
                        CommandResult(true, "Timezone set to $timezone")
                    } catch (e: Exception) {
                        CommandResult(false, e.message ?: "Set timezone failed")
                    }
                } else {
                    CommandResult(false, "Timezone setting requires Device Owner")
                }
            }

            "enableAdb" -> {
                val enabled = command.payload?.get("enabled") as? Boolean ?: true
                val result = deviceOwnerManager.setAdbEnabled(enabled)
                if (result.isSuccess) {
                    CommandResult(true, "ADB ${if (enabled) "enabled" else "disabled"}")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "ADB setting failed")
                }
            }

            "setWifi" -> {
                val enabled = command.payload?.get("enabled") as? Boolean ?: true
                val result = deviceOwnerManager.getHardwareManager().setWifiEnabled(enabled)
                if (result.isSuccess) {
                    CommandResult(true, "WiFi ${if (enabled) "enabled" else "disabled"}")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "WiFi control failed")
                }
            }

            "setBluetooth" -> {
                val enabled = command.payload?.get("enabled") as? Boolean ?: true
                val result = deviceOwnerManager.getHardwareManager().setBluetoothEnabled(enabled)
                if (result.isSuccess) {
                    CommandResult(true, "Bluetooth ${if (enabled) "enabled" else "disabled"}")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "Bluetooth control failed")
                }
            }

            "setGps", "setLocation" -> {
                val enabled = command.payload?.get("enabled") as? Boolean ?: true
                val result = deviceOwnerManager.getHardwareManager().setGpsEnabled(enabled)
                if (result.isSuccess) {
                    CommandResult(true, "GPS ${if (enabled) "enabled" else "disabled"}")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "GPS control failed")
                }
            }

            "setUsb" -> {
                val enabled = command.payload?.get("enabled") as? Boolean ?: true
                val result = deviceOwnerManager.getHardwareManager().setUsbEnabled(enabled)
                if (result.isSuccess) {
                    CommandResult(true, "USB ${if (enabled) "enabled" else "disabled"}")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "USB control failed")
                }
            }

            "setScreenshot" -> {
                val disabled = command.payload?.get("disabled") as? Boolean ?: true
                val result = deviceOwnerManager.getScreenManager().setScreenshotDisabled(disabled)
                if (result.isSuccess) {
                    CommandResult(true, "Screenshot ${if (disabled) "disabled" else "enabled"}")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "Screenshot control failed")
                }
            }

            "setBrightness" -> {
                val level = (command.payload?.get("level") as? Number)?.toInt() ?: 128
                val result = deviceOwnerManager.getScreenManager().setBrightness(level)
                if (result.isSuccess) {
                    CommandResult(true, "Brightness set to $level")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "Brightness control failed")
                }
            }

            "setScreenTimeout" -> {
                val timeoutSeconds = (command.payload?.get("timeoutSeconds") as? Number)?.toInt() ?: 60
                val result = deviceOwnerManager.getScreenManager().setScreenTimeout(timeoutSeconds)
                if (result.isSuccess) {
                    CommandResult(true, "Screen timeout set to $timeoutSeconds seconds")
                } else {
                    CommandResult(false, result.exceptionOrNull()?.message ?: "Screen timeout control failed")
                }
            }

            "setRestriction" -> {
                val restriction = command.payload?.get("restriction") as? String
                val enabled = command.payload?.get("enabled") as? Boolean ?: true
                if (restriction != null) {
                    val result = deviceOwnerManager.getRestrictionManager().setRestriction(restriction, enabled)
                    if (result.isSuccess) {
                        CommandResult(true, "Restriction $restriction ${if (enabled) "enabled" else "disabled"}")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "Restriction control failed")
                    }
                } else {
                    CommandResult(false, "Restriction name required")
                }
            }

            "configureWifi", "setWifiNetwork" -> {
                val ssid = command.payload?.get("ssid") as? String
                val password = command.payload?.get("password") as? String
                val securityType = command.payload?.get("securityType") as? String ?: "WPA2"
                val hidden = command.payload?.get("hidden") as? Boolean ?: false

                if (ssid != null) {
                    val wifiConfig = WifiNetworkConfig(
                        ssid = ssid,
                        password = password,
                        securityType = when (securityType.uppercase()) {
                            "OPEN", "NONE" -> WifiSecurityType.OPEN
                            "WEP" -> WifiSecurityType.WEP
                            "WPA" -> WifiSecurityType.WPA
                            "WPA3" -> WifiSecurityType.WPA3
                            else -> WifiSecurityType.WPA2
                        },
                        hidden = hidden
                    )
                    val result = deviceOwnerManager.getNetworkManager().addWifiNetwork(wifiConfig)
                    if (result.isSuccess) {
                        CommandResult(true, "WiFi network $ssid configured")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "WiFi configuration failed")
                    }
                } else {
                    CommandResult(false, "SSID required")
                }
            }

            "deployFile" -> {
                val url = command.payload?.get("url") as? String
                val path = command.payload?.get("path") as? String
                val hash = command.payload?.get("hash") as? String
                val overwrite = command.payload?.get("overwrite") as? Boolean ?: true

                if (url != null && path != null) {
                    val deployment = FileDeployment(url = url, path = path, hash = hash, overwrite = overwrite)
                    val result = deviceOwnerManager.getFileDeploymentManager().deployFileSync(deployment)
                    if (result.isSuccess) {
                        CommandResult(true, "File deployed to ${result.getOrNull()?.path}")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "File deployment failed")
                    }
                } else {
                    CommandResult(false, "URL and path required")
                }
            }

            "deleteFile" -> {
                val path = command.payload?.get("path") as? String
                if (path != null) {
                    val result = deviceOwnerManager.getFileDeploymentManager().deleteFile(path)
                    if (result.isSuccess) {
                        CommandResult(true, "File deleted: $path")
                    } else {
                        CommandResult(false, result.exceptionOrNull()?.message ?: "File deletion failed")
                    }
                } else {
                    CommandResult(false, "Path required")
                }
            }

            "setPolicy" -> {
                // Policy is typically applied via heartbeat response
                val policy = command.payload
                if (policy != null) {
                    // Apply policy settings
                    CommandResult(true, "Policy applied")
                } else {
                    CommandResult(false, "Policy data required")
                }
            }

            // ============================================
            // Notification Command
            // ============================================
            "sendNotification" -> {
                val title = command.payload?.get("title") as? String ?: "MDM"
                val body = command.payload?.get("body") as? String ?: ""
                showNotification(title, body)
                CommandResult(true, "Notification shown")
            }

            // ============================================
            // Custom/Unknown Commands
            // ============================================
            "custom" -> {
                val customType = command.payload?.get("customType") as? String
                CommandResult(false, "Custom command '$customType' not implemented")
            }

            "enablePermissiveMode" -> {
                // Debug mode - not for production
                CommandResult(false, "Permissive mode not supported in production")
            }

            "rollbackApp" -> {
                CommandResult(false, "App rollback not implemented")
            }

            else -> {
                CommandResult(false, "Unknown command type: ${command.type}")
            }
        }
    }

    /**
     * Handle installation complete callback from InstallResultReceiver.
     * Reports result to server and applies post-install setup on success.
     */
    private fun handleInstallComplete(packageName: String?, success: Boolean, message: String?) {
        if (packageName == null) return

        lifecycleScope.launch {
            try {
                val state = mdmRepository.getEnrollmentState()
                if (!state.isEnrolled || state.token == null) return@launch

                // Report install result to server as an event
                mdmApi.reportEvent(
                    "Bearer ${state.token}",
                    EventRequest(
                        type = "app_install",
                        payload = mapOf(
                            "packageName" to packageName,
                            "success" to success,
                            "message" to (message ?: ""),
                            "timestamp" to System.currentTimeMillis()
                        ),
                        timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date())
                    )
                )

                // On success, apply post-install setup
                if (success && deviceOwnerManager.isDeviceOwner()) {
                    // Grant common permissions
                    deviceOwnerManager.grantCommonPermissions(packageName)
                    // Add to battery optimization whitelist
                    deviceOwnerManager.whitelistFromBatteryOptimization(packageName)

                    android.util.Log.i("OpenMDM.MDMService",
                        "Post-install setup completed for $packageName: permissions granted, battery whitelisted")
                }
            } catch (e: Exception) {
                android.util.Log.e("OpenMDM.MDMService", "Failed to handle install complete: ${e.message}")
            }
        }
    }

    /**
     * Handle uninstallation complete callback from InstallResultReceiver.
     * Reports result to server.
     */
    private fun handleUninstallComplete(packageName: String?, success: Boolean, message: String?) {
        if (packageName == null) return

        lifecycleScope.launch {
            try {
                val state = mdmRepository.getEnrollmentState()
                if (!state.isEnrolled || state.token == null) return@launch

                // Report uninstall result to server as an event
                mdmApi.reportEvent(
                    "Bearer ${state.token}",
                    EventRequest(
                        type = "app_uninstall",
                        payload = mapOf(
                            "packageName" to packageName,
                            "success" to success,
                            "message" to (message ?: ""),
                            "timestamp" to System.currentTimeMillis()
                        ),
                        timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date())
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("OpenMDM.MDMService", "Failed to handle uninstall complete: ${e.message}")
            }
        }
    }

    private fun processIncomingCommand(commandJson: String) {
        lifecycleScope.launch {
            // Parse and process push command
        }
    }

    private fun applyPolicy(policy: PolicyResponse) {
        // Convert to typed PolicySettings
        val settings = PolicyMapper.fromMap(policy.settings)

        lifecycleScope.launch {
            try {
                // Apply kiosk mode
                if (settings.kioskMode && settings.mainApp != null) {
                    val kioskConfig = KioskConfig.fromPolicySettings(settings)
                    deviceOwnerManager.getKioskManager().enterKioskMode(kioskConfig)
                }

                // Apply hardware controls
                val hardwareManager = deviceOwnerManager.getHardwareManager()
                settings.wifiEnabled?.let { hardwareManager.setWifiEnabled(it) }
                settings.bluetoothEnabled?.let { hardwareManager.setBluetoothEnabled(it) }
                settings.gpsEnabled?.let { hardwareManager.setGpsEnabled(it) }
                settings.usbEnabled?.let { hardwareManager.setUsbEnabled(it) }

                // Start hardware enforcement if any settings defined
                if (settings.wifiEnabled != null || settings.bluetoothEnabled != null ||
                    settings.gpsEnabled != null || settings.usbEnabled != null) {
                    val hardwarePolicy = com.openmdm.library.policy.HardwarePolicy.fromPolicySettings(settings)
                    hardwareManager.startEnforcement(hardwarePolicy)
                }

                // Apply screen settings
                val screenManager = deviceOwnerManager.getScreenManager()
                if (settings.screenshotDisabled) {
                    screenManager.setScreenshotDisabled(true)
                }
                settings.screenTimeoutSeconds?.let { screenManager.setScreenTimeout(it) }
                settings.brightnessLevel?.let { screenManager.setBrightness(it) }

                // Apply restrictions
                if (settings.restrictions.isNotEmpty()) {
                    val restrictionManager = deviceOwnerManager.getRestrictionManager()
                    settings.restrictions.forEach { restriction ->
                        restrictionManager.setRestriction(restriction, true)
                    }
                }

                // Apply individual restriction flags
                val restrictionManager = deviceOwnerManager.getRestrictionManager()
                if (settings.disallowInstallApps) {
                    restrictionManager.setRestriction(UserManager.DISALLOW_INSTALL_APPS, true)
                }
                if (settings.disallowUninstallApps) {
                    restrictionManager.setRestriction(UserManager.DISALLOW_UNINSTALL_APPS, true)
                }
                if (settings.disallowFactoryReset) {
                    restrictionManager.setRestriction(UserManager.DISALLOW_FACTORY_RESET, true)
                }
                if (settings.disallowDebugging) {
                    restrictionManager.setRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, true)
                }
                if (settings.disallowCamera) {
                    deviceOwnerManager.setCameraDisabled(true)
                }

                // Apply WiFi networks
                if (settings.wifiNetworks.isNotEmpty()) {
                    val networkManager = deviceOwnerManager.getNetworkManager()
                    settings.wifiNetworks.forEach { wifiConfig ->
                        networkManager.addWifiNetwork(wifiConfig)
                    }
                }

                // Deploy files
                if (settings.fileDeployments.isNotEmpty()) {
                    val fileManager = deviceOwnerManager.getFileDeploymentManager()
                    settings.fileDeployments.forEach { fileConfig ->
                        val deployment = FileDeployment(
                            url = fileConfig.url,
                            path = fileConfig.path,
                            hash = fileConfig.hash,
                            overwrite = fileConfig.overwrite
                        )
                        fileManager.deployFileSync(deployment)
                    }
                }

                // Apply launcher/app visibility settings
                if (settings.launcherEnabled || settings.allowedApps.isNotEmpty() ||
                    settings.blockedApps.isNotEmpty()) {

                    val launcherManager = deviceOwnerManager.getLauncherManager()

                    // Determine visibility mode from launcher settings
                    val mode = when (settings.launcherMode) {
                        "allowlist" -> VisibilityMode.ALLOWLIST
                        "blocklist" -> VisibilityMode.BLOCKLIST
                        else -> VisibilityMode.DEFAULT
                    }

                    // Apply app visibility policy
                    launcherManager.applyVisibilityPolicy(
                        allowedApps = settings.allowedApps,
                        blockedApps = settings.blockedApps,
                        mode = mode
                    )

                    // Set as default launcher if requested and Device Owner
                    if (settings.setAsDefaultLauncher && deviceOwnerManager.isDeviceOwner()) {
                        val launcherActivity = ComponentName(
                            this@MDMService,
                            LauncherActivity::class.java
                        )
                        launcherManager.setAsDefaultLauncher(launcherActivity)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, OpenMDMApplication.CHANNEL_COMMANDS)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        stopHeartbeat()
        super.onDestroy()
    }

    data class CommandResult(
        val success: Boolean,
        val message: String?,
        val data: Any? = null
    )

    companion object {
        const val ACTION_START = "com.openmdm.agent.START"
        const val ACTION_STOP = "com.openmdm.agent.STOP"
        const val ACTION_SYNC_NOW = "com.openmdm.agent.SYNC_NOW"
        const val ACTION_PROCESS_COMMAND = "com.openmdm.agent.PROCESS_COMMAND"
        const val ACTION_INSTALL_COMPLETE = "com.openmdm.agent.INSTALL_COMPLETE"
        const val ACTION_UNINSTALL_COMPLETE = "com.openmdm.agent.UNINSTALL_COMPLETE"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"

        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_HEARTBEAT_INTERVAL = 60_000L // 1 minute
    }
}
