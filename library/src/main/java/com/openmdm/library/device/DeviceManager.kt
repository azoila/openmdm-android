package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import com.openmdm.library.file.FileDeploymentManager
import com.openmdm.library.security.ApkIntegrity
import com.openmdm.library.telemetry.MdmTelemetryHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URL

/**
 * OpenMDM Device Manager
 *
 * Core Device Owner/Device Admin functionality for MDM operations.
 * This is the library version that can be embedded in any Android app.
 *
 * Usage:
 * ```kotlin
 * val deviceManager = DeviceManager.create(context, MyDeviceAdminReceiver::class.java)
 * if (deviceManager.isDeviceOwner()) {
 *     deviceManager.installApkSilently(url, packageName)
 * }
 * ```
 */
class DeviceManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName,
    private val installResultAction: String = DEFAULT_INSTALL_ACTION,
    private val uninstallResultAction: String = DEFAULT_UNINSTALL_ACTION
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val packageManager: PackageManager = context.packageManager

    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    companion object {
        const val DEFAULT_INSTALL_ACTION = "com.openmdm.INSTALL_RESULT"
        const val DEFAULT_UNINSTALL_ACTION = "com.openmdm.UNINSTALL_RESULT"

        /**
         * Create DeviceManager with a DeviceAdminReceiver class
         */
        fun <T : android.app.admin.DeviceAdminReceiver> create(
            context: Context,
            adminReceiverClass: Class<T>,
            installResultAction: String = DEFAULT_INSTALL_ACTION,
            uninstallResultAction: String = DEFAULT_UNINSTALL_ACTION
        ): DeviceManager {
            val adminComponent = ComponentName(context, adminReceiverClass)
            return DeviceManager(context, adminComponent, installResultAction, uninstallResultAction)
        }

        /**
         * Create DeviceManager with explicit ComponentName
         */
        fun create(
            context: Context,
            adminComponent: ComponentName,
            installResultAction: String = DEFAULT_INSTALL_ACTION,
            uninstallResultAction: String = DEFAULT_UNINSTALL_ACTION
        ): DeviceManager {
            return DeviceManager(context, adminComponent, installResultAction, uninstallResultAction)
        }
    }

    // ============================================
    // Status Checks
    // ============================================

    fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(context.packageName)
    fun isDeviceAdmin(): Boolean = devicePolicyManager.isAdminActive(adminComponent)
    fun isProfileOwner(): Boolean = devicePolicyManager.isProfileOwnerApp(context.packageName)

    // ============================================
    // Device Control
    // ============================================

    fun lockDevice(): Result<Unit> = runCatching {
        require(isDeviceAdmin()) { "Device admin not enabled" }
        devicePolicyManager.lockNow()
    }

    fun rebootDevice(): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Device owner not enabled" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { "Reboot requires Android N+" }
        devicePolicyManager.reboot(adminComponent)
    }

    fun wipeDevice(flags: Int = 0): Result<Unit> = runCatching {
        require(isDeviceAdmin()) { "Device admin not enabled" }
        devicePolicyManager.wipeData(flags)
    }

    // ============================================
    // App Installation
    // ============================================

    /**
     * Install an APK silently (requires Device Owner).
     *
     * [expectedSha256] is the hex-encoded SHA-256 of the APK, as supplied by the
     * server. It is verified **before** the file is handed to PackageInstaller.
     *
     * When it is absent, the APK is installed unverified, and the integrity of
     * the install rests entirely on TLS. A compromised or MITM'd download channel
     * then becomes arbitrary code execution as Device Owner — which is why
     * [requireHash] exists: set it and an install command that carries no hash is
     * refused outright. Production fleets should.
     */
    suspend fun installApkSilently(
        apkUrl: String,
        packageName: String,
        resultReceiverClass: Class<*>? = null,
        expectedSha256: String? = null,
        requireHash: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(isDeviceOwner()) { "Silent install requires Device Owner" }

            if (requireHash && expectedSha256.isNullOrBlank()) {
                throw SecurityException(
                    "Install of $packageName rejected: no expectedSha256 was supplied " +
                        "and requireHash is set",
                )
            }

            val apkFile = downloadApk(apkUrl, packageName)
            try {
                if (!expectedSha256.isNullOrBlank()) {
                    val actual = ApkIntegrity.sha256(apkFile)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        MdmTelemetryHolder.event(
                            "apk_hash_mismatch",
                            mapOf(
                                "package" to packageName,
                                "expected" to expectedSha256,
                                "actual" to actual,
                            ),
                        )
                        throw SecurityException(
                            "APK hash verification failed for $packageName " +
                                "(expected $expectedSha256, got $actual)",
                        )
                    }
                } else {
                    MdmTelemetryHolder.event(
                        "apk_install_unverified",
                        mapOf("package" to packageName),
                    )
                }

                installWithPackageInstaller(apkFile, packageName, resultReceiverClass)
            } finally {
                apkFile.delete()
            }
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

    private fun installWithPackageInstaller(
        apkFile: File,
        packageName: String,
        resultReceiverClass: Class<*>?
    ) {
        val packageInstaller = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setInstallReason(PackageManager.INSTALL_REASON_DEVICE_SETUP)
        }
        params.setAppPackageName(packageName)

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        FileInputStream(apkFile).use { input ->
            session.openWrite("package", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }

        val pendingIntent = if (resultReceiverClass != null) {
            val intent = Intent(context, resultReceiverClass).apply {
                action = installResultAction
                putExtra("packageName", packageName)
            }
            android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
        } else {
            // Create a no-op pending intent
            val intent = Intent()
            android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        session.commit(pendingIntent.intentSender)
        session.close()
    }

    fun uninstallAppSilently(
        packageName: String,
        resultReceiverClass: Class<*>? = null
    ): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Silent uninstall requires Device Owner" }

        val packageInstaller = packageManager.packageInstaller

        val pendingIntent = if (resultReceiverClass != null) {
            val intent = Intent(context, resultReceiverClass).apply {
                action = uninstallResultAction
                putExtra("packageName", packageName)
            }
            android.app.PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
        } else {
            val intent = Intent()
            android.app.PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        packageInstaller.uninstall(packageName, pendingIntent.intentSender)
    }

    // ============================================
    // Permission Management
    // ============================================

    fun grantPermission(packageName: String, permission: String): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Permission grant requires Device Owner" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { "Requires Android M+" }

        val success = devicePolicyManager.setPermissionGrantState(
            adminComponent,
            packageName,
            permission,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        )
        require(success) { "Failed to grant permission" }
    }

    fun grantPermissions(packageName: String, permissions: List<String>): Map<String, Boolean> {
        return permissions.associateWith { permission ->
            grantPermission(packageName, permission).isSuccess
        }
    }

    fun grantCommonPermissions(packageName: String): Result<Unit> = runCatching {
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
        commonPermissions.forEach { grantPermission(packageName, it) }
    }

    // ============================================
    // Battery/Power Management
    // ============================================

    fun whitelistFromBatteryOptimization(packageName: String): Result<Unit> = runCatching {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { "Requires Android M+" }

        if (isDeviceOwner()) {
            devicePolicyManager.setPackagesSuspended(adminComponent, arrayOf(packageName), false)

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Runtime.getRuntime().exec(
                    arrayOf("cmd", "deviceidle", "whitelist", "+$packageName")
                ).waitFor()
            }
        }
    }

    fun isWhitelistedFromBatteryOptimization(packageName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    // ============================================
    // Kiosk Mode / Lock Task
    // ============================================

    fun setLockTaskPackages(packages: List<String>): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Lock task requires Device Owner" }
        devicePolicyManager.setLockTaskPackages(adminComponent, packages.toTypedArray())
    }

    fun startLockTaskMode(packageName: String): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Lock task requires Device Owner" }

        devicePolicyManager.setLockTaskPackages(
            adminComponent,
            arrayOf(packageName, context.packageName)
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("Could not find launch intent for $packageName")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    }

    // ============================================
    // System Settings
    // ============================================

    fun setScreenBrightness(level: Int): Result<Unit> = runCatching {
        require(level in 0..255) { "Brightness must be 0-255" }
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
    }

    fun setVolume(streamType: Int, volume: Int): Result<Unit> = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.setStreamVolume(streamType, volume, 0)
    }

    fun setAdbEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "ADB control requires Device Owner" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { "Requires Android L+" }

        if (enabled) {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
        } else {
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
    }

    // ============================================
    // User Restrictions
    // ============================================

    fun setUserRestriction(restriction: String, enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "User restrictions require Device Owner" }
        if (enabled) {
            devicePolicyManager.addUserRestriction(adminComponent, restriction)
        } else {
            devicePolicyManager.clearUserRestriction(adminComponent, restriction)
        }
    }

    fun setCameraDisabled(disabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceAdmin()) { "Camera control requires Device Admin" }
        devicePolicyManager.setCameraDisabled(adminComponent, disabled)
    }

    fun setPasswordQuality(quality: Int): Result<Unit> = runCatching {
        require(isDeviceAdmin()) { "Password policy requires Device Admin" }
        devicePolicyManager.setPasswordQuality(adminComponent, quality)
    }

    fun setPasswordMinimumLength(length: Int): Result<Unit> = runCatching {
        require(isDeviceAdmin()) { "Password policy requires Device Admin" }
        devicePolicyManager.setPasswordMinimumLength(adminComponent, length)
    }

    // ============================================
    // Manager Accessors
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
     * Get LauncherManager for kiosk/home-screen control.
     *
     * The library shipped a LauncherManager and no way to reach it from
     * DeviceManager — the agent had its own accessor, so an embedder could not
     * get at a manager the library itself provides.
     */
    fun getLauncherManager(): LauncherManager {
        return _launcherManager ?: LauncherManager.create(context, adminComponent).also {
            _launcherManager = it
        }
    }

    // ============================================
    // Secure Update
    // ============================================

    /**
     * Install an APK with the checks a production update needs:
     * SHA-256 verification, downgrade protection, a minimum-previous-version
     * gate, and — for a self-update — a state backup taken before the process
     * replaces itself.
     *
     * [installApkSilently] is the plain path. This is the one to use when the
     * APK is an upgrade to something already installed, and especially when it
     * is *this* app.
     */
    suspend fun installApkSecurely(
        params: SecureUpdateParams,
        resultReceiverClass: Class<*>? = null,
    ): UpdateResult = withContext(Dispatchers.IO) {
        if (!isDeviceOwner()) {
            return@withContext UpdateResult(
                success = false,
                message = "Silent install requires Device Owner",
                error = "DEVICE_OWNER_REQUIRED",
            )
        }

        val (currentVersion, currentVersionCode) =
            SecureUpdate.installedVersion(context.packageManager, params.packageName)

        SecureUpdate.rejectionReason(params, currentVersion, currentVersionCode)?.let {
            return@withContext it
        }

        // The process is about to be replaced. If the new build cannot read the
        // old state, the device comes back up as a factory-fresh agent that has
        // forgotten it was ever enrolled.
        if (params.isSelfUpdate) {
            SecureUpdate.backupAppState(context)
        }

        try {
            val apkFile = downloadApk(params.apkUrl, params.packageName)
            try {
                if (!SecureUpdate.verify(apkFile, params.expectedSha256)) {
                    return@withContext UpdateResult(
                        success = false,
                        message = "APK hash verification failed for ${params.packageName}",
                        fromVersion = currentVersion,
                        toVersion = params.targetVersion,
                        error = "HASH_MISMATCH",
                    )
                }

                installWithPackageInstaller(apkFile, params.packageName, resultReceiverClass)

                UpdateResult(
                    success = true,
                    message = "Update initiated",
                    fromVersion = currentVersion,
                    toVersion = params.targetVersion,
                )
            } finally {
                apkFile.delete()
            }
        } catch (t: Throwable) {
            UpdateResult(
                success = false,
                message = t.message ?: "Update failed",
                fromVersion = currentVersion,
                toVersion = params.targetVersion,
                error = "EXCEPTION",
            )
        }
    }

    /** Restore state saved before a self-update. Call on first boot after one. */
    fun restoreAppState() = SecureUpdate.restoreAppState(context)

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
