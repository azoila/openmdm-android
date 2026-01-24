package com.openmdm.agent.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import com.openmdm.agent.receiver.MDMDeviceAdminReceiver
import com.openmdm.library.device.HardwareManager
import com.openmdm.library.device.KioskManager
import com.openmdm.library.device.LauncherManager
import com.openmdm.library.device.NetworkManager
import com.openmdm.library.device.RestrictionManager
import com.openmdm.library.device.ScreenManager
import com.openmdm.library.file.FileDeploymentManager
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device Owner Manager
 *
 * Provides Device Owner/Device Admin functionality for MDM operations.
 * Handles silent app installation, permission granting, device control, etc.
 */
@Singleton
class DeviceOwnerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val packageManager: PackageManager = context.packageManager

    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val adminComponent: ComponentName =
        MDMDeviceAdminReceiver.getComponentName(context)

    /**
     * Check if this app is a Device Owner
     */
    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Check if this app is a Device Admin
     */
    fun isDeviceAdmin(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }

    /**
     * Check if this app is either Device Owner or Profile Owner
     */
    fun isProfileOwner(): Boolean {
        return devicePolicyManager.isProfileOwnerApp(context.packageName)
    }

    // ============================================
    // Device Control Commands
    // ============================================

    /**
     * Lock the device immediately
     */
    fun lockDevice(): Result<Unit> {
        return try {
            if (isDeviceAdmin()) {
                devicePolicyManager.lockNow()
                Result.success(Unit)
            } else {
                Result.failure(SecurityException("Device admin not enabled"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reboot the device (requires Device Owner)
     */
    fun rebootDevice(): Result<Unit> {
        return try {
            if (isDeviceOwner()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    devicePolicyManager.reboot(adminComponent)
                    Result.success(Unit)
                } else {
                    Result.failure(UnsupportedOperationException("Reboot requires Android N or higher"))
                }
            } else {
                Result.failure(SecurityException("Device owner not enabled"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Wipe device data (factory reset)
     */
    fun wipeDevice(preserveData: Boolean = false): Result<Unit> {
        return try {
            if (isDeviceAdmin()) {
                val flags = if (preserveData) {
                    DevicePolicyManager.WIPE_EXTERNAL_STORAGE
                } else {
                    0
                }
                devicePolicyManager.wipeData(flags)
                Result.success(Unit)
            } else {
                Result.failure(SecurityException("Device admin not enabled"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // App Installation Commands
    // ============================================

    companion object {
        private const val TAG = "DeviceOwnerManager"
    }

    /**
     * Data class for secure update parameters
     */
    data class SecureUpdateParams(
        val apkUrl: String,
        val packageName: String,
        val expectedSha256: String,
        val targetVersion: String,
        val targetVersionCode: Int,
        val minPreviousVersion: String? = null,
        val isSelfUpdate: Boolean = false
    )

    /**
     * Data class for update result with detailed status
     */
    data class UpdateResult(
        val success: Boolean,
        val message: String,
        val fromVersion: String? = null,
        val toVersion: String? = null,
        val error: String? = null
    )

    /**
     * Install APK silently with security verification (requires Device Owner)
     *
     * Security features:
     * - SHA-256 hash verification
     * - Downgrade protection
     * - State backup for agent self-updates
     * - Version constraint checking
     */
    suspend fun installApkSecurely(params: SecureUpdateParams): UpdateResult = withContext(Dispatchers.IO) {
        try {
            if (!isDeviceOwner()) {
                return@withContext UpdateResult(
                    success = false,
                    message = "Silent install requires Device Owner",
                    error = "DEVICE_OWNER_REQUIRED"
                )
            }

            // Get current version info
            val currentVersionInfo = try {
                val packageInfo = packageManager.getPackageInfo(params.packageName, 0)
                Pair(packageInfo.versionName ?: "unknown", packageInfo.longVersionCode.toInt())
            } catch (e: PackageManager.NameNotFoundException) {
                Pair("0.0.0", 0)
            }

            val (currentVersion, currentVersionCode) = currentVersionInfo

            Log.i(TAG, "🔄 Secure update: ${params.packageName} from $currentVersion to ${params.targetVersion}")

            // Downgrade protection: prevent installing older versions
            if (params.targetVersionCode < currentVersionCode) {
                Log.w(TAG, "⛔ Downgrade attempt blocked: $currentVersionCode -> ${params.targetVersionCode}")
                return@withContext UpdateResult(
                    success = false,
                    message = "Downgrade not allowed",
                    fromVersion = currentVersion,
                    toVersion = params.targetVersion,
                    error = "DOWNGRADE_BLOCKED"
                )
            }

            // Check minimum previous version constraint
            if (params.minPreviousVersion != null) {
                if (compareVersions(currentVersion, params.minPreviousVersion) < 0) {
                    Log.w(TAG, "⛔ Version constraint failed: $currentVersion < ${params.minPreviousVersion}")
                    return@withContext UpdateResult(
                        success = false,
                        message = "Current version too old. Update to ${params.minPreviousVersion} first.",
                        fromVersion = currentVersion,
                        toVersion = params.targetVersion,
                        error = "VERSION_CONSTRAINT_FAILED"
                    )
                }
            }

            // Backup state for agent self-update
            if (params.isSelfUpdate) {
                Log.i(TAG, "📦 Backing up agent state before self-update")
                backupAgentState()
            }

            // Download APK
            Log.i(TAG, "⬇️ Downloading APK from ${params.apkUrl}")
            val apkFile = downloadApk(params.apkUrl, params.packageName)

            // Verify SHA-256 hash
            Log.i(TAG, "🔐 Verifying SHA-256 hash")
            val actualHash = calculateSha256(apkFile)
            if (!actualHash.equals(params.expectedSha256, ignoreCase = true)) {
                apkFile.delete()
                Log.e(TAG, "⛔ Hash mismatch! Expected: ${params.expectedSha256}, Got: $actualHash")
                return@withContext UpdateResult(
                    success = false,
                    message = "APK hash verification failed",
                    fromVersion = currentVersion,
                    toVersion = params.targetVersion,
                    error = "HASH_MISMATCH"
                )
            }
            Log.i(TAG, "✅ Hash verified: $actualHash")

            // Install using PackageInstaller
            Log.i(TAG, "📲 Installing APK via PackageInstaller")
            val result = installWithPackageInstaller(apkFile, params.packageName)

            // Clean up
            apkFile.delete()

            if (result.isSuccess) {
                Log.i(TAG, "✅ Update initiated: ${params.packageName} -> ${params.targetVersion}")
                UpdateResult(
                    success = true,
                    message = "Update initiated successfully",
                    fromVersion = currentVersion,
                    toVersion = params.targetVersion
                )
            } else {
                Log.e(TAG, "❌ Update failed: ${result.exceptionOrNull()?.message}")
                UpdateResult(
                    success = false,
                    message = result.exceptionOrNull()?.message ?: "Installation failed",
                    fromVersion = currentVersion,
                    toVersion = params.targetVersion,
                    error = "INSTALL_FAILED"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Secure update exception", e)
            UpdateResult(
                success = false,
                message = e.message ?: "Unknown error",
                error = "EXCEPTION"
            )
        }
    }

    /**
     * Install APK silently (requires Device Owner) - Legacy method, use installApkSecurely for production
     */
    suspend fun installApkSilently(apkUrl: String, packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isDeviceOwner()) {
                return@withContext Result.failure(SecurityException("Silent install requires Device Owner"))
            }

            // Download APK
            val apkFile = downloadApk(apkUrl, packageName)

            // Install using PackageInstaller
            val result = installWithPackageInstaller(apkFile, packageName)

            // Clean up
            apkFile.delete()

            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Calculate SHA-256 hash of a file
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Compare semantic versions (e.g., "1.2.3" vs "1.2.4")
     * Returns: negative if v1 < v2, 0 if equal, positive if v1 > v2
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    /**
     * Backup agent state before self-update
     */
    private fun backupAgentState() {
        try {
            val backupDir = File(context.filesDir, "agent_backup")
            if (!backupDir.exists()) backupDir.mkdirs()

            // Backup shared preferences
            val prefsDir = File(context.dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.listFiles()?.forEach { file ->
                    file.copyTo(File(backupDir, file.name), overwrite = true)
                }
            }

            // Create backup marker with timestamp
            File(backupDir, "backup_marker.txt").writeText(
                "backup_time=${System.currentTimeMillis()}\n" +
                "version=${context.packageManager.getPackageInfo(context.packageName, 0).versionName}\n"
            )

            Log.i(TAG, "✅ Agent state backed up to $backupDir")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Failed to backup agent state", e)
        }
    }

    /**
     * Restore agent state after update (called from BootReceiver)
     */
    fun restoreAgentState(): Boolean {
        try {
            val backupDir = File(context.filesDir, "agent_backup")
            if (!backupDir.exists()) {
                Log.d(TAG, "No backup to restore")
                return false
            }

            val markerFile = File(backupDir, "backup_marker.txt")
            if (!markerFile.exists()) {
                Log.d(TAG, "No backup marker found")
                return false
            }

            // Check if backup is recent (within last hour)
            val markerContent = markerFile.readText()
            val backupTime = markerContent.lines()
                .find { it.startsWith("backup_time=") }
                ?.substringAfter("=")?.toLongOrNull() ?: 0

            if (System.currentTimeMillis() - backupTime > 3600000) {
                Log.d(TAG, "Backup too old, skipping restore")
                backupDir.deleteRecursively()
                return false
            }

            Log.i(TAG, "🔄 Restoring agent state from backup")

            // Restore shared preferences
            val prefsDir = File(context.dataDir, "shared_prefs")
            backupDir.listFiles()?.filter { it.name.endsWith(".xml") }?.forEach { file ->
                file.copyTo(File(prefsDir, file.name), overwrite = true)
            }

            // Clean up backup
            backupDir.deleteRecursively()

            Log.i(TAG, "✅ Agent state restored successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Failed to restore agent state", e)
            return false
        }
    }

    private suspend fun downloadApk(url: String, packageName: String): File = withContext(Dispatchers.IO) {
        val apkFile = File(context.cacheDir, "$packageName.apk")

        URL(url).openStream().use { input ->
            apkFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        apkFile
    }

    private fun installWithPackageInstaller(apkFile: File, packageName: String): Result<Unit> {
        return try {
            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            // Grant all runtime permissions automatically
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setInstallReason(PackageManager.INSTALL_REASON_DEVICE_SETUP)
            }
            params.setAppPackageName(packageName)

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Write APK to session
            FileInputStream(apkFile).use { input ->
                session.openWrite("package", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            // Create pending intent for result
            val intent = Intent(context, InstallResultReceiver::class.java)
            intent.action = "com.openmdm.agent.INSTALL_RESULT"
            intent.putExtra("packageName", packageName)

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

            // Commit session
            session.commit(pendingIntent.intentSender)
            session.close()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uninstall app silently (requires Device Owner)
     */
    fun uninstallAppSilently(packageName: String): Result<Unit> {
        return try {
            if (!isDeviceOwner()) {
                return Result.failure(SecurityException("Silent uninstall requires Device Owner"))
            }

            val packageInstaller = packageManager.packageInstaller

            val intent = Intent(context, InstallResultReceiver::class.java)
            intent.action = "com.openmdm.agent.UNINSTALL_RESULT"
            intent.putExtra("packageName", packageName)

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

            packageInstaller.uninstall(packageName, pendingIntent.intentSender)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // Permission Management
    // ============================================

    /**
     * Grant runtime permission to an app (requires Device Owner)
     */
    fun grantPermission(packageName: String, permission: String): Result<Unit> {
        return try {
            if (!isDeviceOwner()) {
                return Result.failure(SecurityException("Permission grant requires Device Owner"))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val result = devicePolicyManager.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )

                if (result) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to grant permission"))
                }
            } else {
                Result.failure(UnsupportedOperationException("Permission management requires Android M or higher"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Grant multiple permissions to an app
     */
    fun grantPermissions(packageName: String, permissions: List<String>): Result<Map<String, Boolean>> {
        val results = mutableMapOf<String, Boolean>()

        for (permission in permissions) {
            val result = grantPermission(packageName, permission)
            results[permission] = result.isSuccess
        }

        return Result.success(results)
    }

    /**
     * Common permissions to auto-grant for MDM-managed apps
     */
    fun grantCommonPermissions(packageName: String): Result<Unit> {
        val commonPermissions = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_PHONE_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS"
        )

        return try {
            for (permission in commonPermissions) {
                grantPermission(packageName, permission)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // Battery/Power Management
    // ============================================

    /**
     * Add app to battery optimization whitelist
     */
    fun whitelistFromBatteryOptimization(packageName: String): Result<Unit> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (isDeviceOwner()) {
                    // Use device owner API
                    devicePolicyManager.setPackagesSuspended(
                        adminComponent,
                        arrayOf(packageName),
                        false
                    )
                }

                // Also use power whitelist if possible
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    // For device owner, we can use shell command
                    if (isDeviceOwner()) {
                        val process = Runtime.getRuntime().exec(
                            arrayOf("cmd", "deviceidle", "whitelist", "+$packageName")
                        )
                        process.waitFor()
                    }
                }

                Result.success(Unit)
            } else {
                Result.failure(UnsupportedOperationException("Battery whitelist requires Android M or higher"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if app is whitelisted from battery optimization
     */
    fun isWhitelistedFromBatteryOptimization(packageName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
    }

    // ============================================
    // Kiosk Mode / Lock Task
    // ============================================

    /**
     * Set allowed lock task packages (for kiosk mode)
     */
    fun setLockTaskPackages(packages: List<String>): Result<Unit> {
        return try {
            if (!isDeviceOwner()) {
                return Result.failure(SecurityException("Lock task requires Device Owner"))
            }

            devicePolicyManager.setLockTaskPackages(
                adminComponent,
                packages.toTypedArray()
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Start lock task mode for a package (kiosk mode)
     */
    fun startLockTaskMode(packageName: String): Result<Unit> {
        return try {
            if (!isDeviceOwner()) {
                return Result.failure(SecurityException("Lock task requires Device Owner"))
            }

            // First, allow the package
            devicePolicyManager.setLockTaskPackages(
                adminComponent,
                arrayOf(packageName, context.packageName)
            )

            // Launch the app in lock task mode
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Could not find launch intent for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // System Settings
    // ============================================

    /**
     * Set screen brightness
     */
    fun setScreenBrightness(level: Int): Result<Unit> {
        return try {
            if (level < 0 || level > 255) {
                return Result.failure(IllegalArgumentException("Brightness must be between 0 and 255"))
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                level
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set system volume
     */
    fun setVolume(streamType: Int, volume: Int): Result<Unit> {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.setStreamVolume(streamType, volume, 0)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Enable/disable ADB (requires Device Owner)
     */
    fun setAdbEnabled(enabled: Boolean): Result<Unit> {
        return try {
            if (!isDeviceOwner()) {
                return Result.failure(SecurityException("ADB control requires Device Owner"))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                devicePolicyManager.addUserRestriction(
                    adminComponent,
                    if (enabled) null else UserManager.DISALLOW_DEBUGGING_FEATURES
                )
                Result.success(Unit)
            } else {
                Result.failure(UnsupportedOperationException("ADB control requires Android L or higher"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // User Restrictions
    // ============================================

    /**
     * Set user restriction
     */
    fun setUserRestriction(restriction: String, enabled: Boolean): Result<Unit> {
        return try {
            if (!isDeviceOwner()) {
                return Result.failure(SecurityException("User restrictions require Device Owner"))
            }

            if (enabled) {
                devicePolicyManager.addUserRestriction(adminComponent, restriction)
            } else {
                devicePolicyManager.clearUserRestriction(adminComponent, restriction)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Disable camera
     */
    fun setCameraDisabled(disabled: Boolean): Result<Unit> {
        return try {
            if (!isDeviceAdmin()) {
                return Result.failure(SecurityException("Camera control requires Device Admin"))
            }

            devicePolicyManager.setCameraDisabled(adminComponent, disabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set password quality requirements
     */
    fun setPasswordQuality(quality: Int): Result<Unit> {
        return try {
            if (!isDeviceAdmin()) {
                return Result.failure(SecurityException("Password policy requires Device Admin"))
            }

            devicePolicyManager.setPasswordQuality(adminComponent, quality)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set minimum password length
     */
    fun setPasswordMinimumLength(length: Int): Result<Unit> {
        return try {
            if (!isDeviceAdmin()) {
                return Result.failure(SecurityException("Password policy requires Device Admin"))
            }

            devicePolicyManager.setPasswordMinimumLength(adminComponent, length)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // Manager Accessors (Library Integration)
    // ============================================

    private var _hardwareManager: HardwareManager? = null
    private var _screenManager: ScreenManager? = null
    private var _kioskManager: KioskManager? = null
    private var _restrictionManager: RestrictionManager? = null
    private var _networkManager: NetworkManager? = null
    private var _fileDeploymentManager: FileDeploymentManager? = null
    private var _launcherManager: LauncherManager? = null

    /**
     * Get HardwareManager for WiFi, Bluetooth, GPS, USB control
     */
    fun getHardwareManager(): HardwareManager {
        return _hardwareManager ?: HardwareManager.create(context, adminComponent).also {
            _hardwareManager = it
        }
    }

    /**
     * Get ScreenManager for screenshot, timeout, brightness control
     */
    fun getScreenManager(): ScreenManager {
        return _screenManager ?: ScreenManager.create(context, adminComponent).also {
            _screenManager = it
        }
    }

    /**
     * Get KioskManager for lock task mode and kiosk controls
     */
    fun getKioskManager(): KioskManager {
        return _kioskManager ?: KioskManager.create(context, adminComponent).also {
            _kioskManager = it
        }
    }

    /**
     * Get RestrictionManager for user restrictions
     */
    fun getRestrictionManager(): RestrictionManager {
        return _restrictionManager ?: RestrictionManager.create(context, adminComponent).also {
            _restrictionManager = it
        }
    }

    /**
     * Get NetworkManager for WiFi network configuration
     */
    fun getNetworkManager(): NetworkManager {
        return _networkManager ?: NetworkManager.create(context, adminComponent).also {
            _networkManager = it
        }
    }

    /**
     * Get FileDeploymentManager for file downloads and deployment
     */
    fun getFileDeploymentManager(): FileDeploymentManager {
        return _fileDeploymentManager ?: FileDeploymentManager.create(context).also {
            _fileDeploymentManager = it
        }
    }

    /**
     * Get LauncherManager for app visibility and launcher controls
     */
    fun getLauncherManager(): LauncherManager {
        return _launcherManager ?: LauncherManager.create(context, adminComponent).also {
            _launcherManager = it
        }
    }

    /**
     * Clean up manager resources
     */
    fun destroy() {
        _hardwareManager?.destroy()
        _hardwareManager = null
        _screenManager = null
        _kioskManager = null
        _restrictionManager = null
        _networkManager = null
        _fileDeploymentManager = null
        _launcherManager = null
    }
}
