package com.openmdm.library.device

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.openmdm.library.policy.KioskConfig

/**
 * Kiosk Mode Manager
 *
 * Provides advanced kiosk/lock task mode controls with granular feature configuration.
 * Supports Android's Lock Task Mode with customizable features (Android 9+).
 *
 * Features:
 * - Lock task mode with allowed packages
 * - Status bar lock
 * - Keyguard disable
 * - Lock task feature flags (home, recents, notifications, etc.)
 * - Immersive mode helpers
 *
 * Usage:
 * ```kotlin
 * val kioskManager = KioskManager.create(context, adminComponent)
 * kioskManager.enterKioskMode(KioskConfig(
 *     mainApp = "com.example.kiosk",
 *     homeEnabled = false,
 *     statusBarLocked = true
 * ))
 * ```
 */
class KioskManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val packageManager: PackageManager = context.packageManager

    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    companion object {
        /**
         * Create KioskManager with a DeviceAdminReceiver class
         */
        fun <T : android.app.admin.DeviceAdminReceiver> create(
            context: Context,
            adminReceiverClass: Class<T>
        ): KioskManager {
            val adminComponent = ComponentName(context, adminReceiverClass)
            return KioskManager(context, adminComponent)
        }

        /**
         * Create KioskManager with explicit ComponentName
         */
        fun create(context: Context, adminComponent: ComponentName): KioskManager {
            return KioskManager(context, adminComponent)
        }
    }

    // ============================================
    // Status Checks
    // ============================================

    private fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(context.packageName)

    /**
     * Check if currently in lock task mode
     */
    fun isInLockTaskMode(): Boolean {
        return activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /**
     * Get the type of lock task mode
     */
    fun getLockTaskModeState(): Int {
        return activityManager.lockTaskModeState
    }

    // ============================================
    // Kiosk Mode Entry/Exit
    // ============================================

    /**
     * Enter kiosk mode with the specified configuration.
     *
     * This sets up lock task mode with the allowed packages and feature flags.
     * The main app will be launched after configuration.
     */
    fun enterKioskMode(config: KioskConfig): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Kiosk mode requires Device Owner" }
        require(config.mainApp != null) { "Main app package is required" }

        // Build list of allowed packages
        val allowedPackages = buildList {
            add(context.packageName) // Always include MDM app
            add(config.mainApp!!)
            addAll(config.allowedPackages)
        }.distinct().toTypedArray()

        // Set lock task packages
        devicePolicyManager.setLockTaskPackages(adminComponent, allowedPackages)

        // Set lock task features (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val features = buildLockTaskFeatures(config)
            devicePolicyManager.setLockTaskFeatures(adminComponent, features)
        }

        // Lock status bar if requested
        if (config.statusBarLocked) {
            setStatusBarDisabled(true)
        }

        // Disable keyguard if requested
        if (!config.keyguardEnabled) {
            setKeyguardDisabled(true)
        }

        // Launch the main app in lock task mode
        launchInLockTask(config.mainApp!!)
    }

    /**
     * Exit kiosk mode and restore normal operation.
     */
    fun exitKioskMode(): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Exit kiosk mode requires Device Owner" }

        // Clear lock task packages
        devicePolicyManager.setLockTaskPackages(adminComponent, emptyArray())

        // Reset lock task features
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            devicePolicyManager.setLockTaskFeatures(adminComponent, 0)
        }

        // Re-enable status bar
        setStatusBarDisabled(false)

        // Re-enable keyguard
        setKeyguardDisabled(false)
    }

    /**
     * Build lock task feature flags from config
     */
    private fun buildLockTaskFeatures(config: KioskConfig): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0

        var features = 0

        if (config.homeEnabled) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_HOME
        }
        if (config.recentsEnabled) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
        }
        if (config.notificationsEnabled) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
        }
        if (config.systemInfoEnabled) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
        }
        if (config.globalActionsEnabled) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        }
        if (config.keyguardEnabled) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
        }

        return features
    }

    // ============================================
    // Lock Task Package Management
    // ============================================

    /**
     * Set packages allowed in lock task mode
     */
    fun setLockTaskPackages(packages: List<String>): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Lock task requires Device Owner" }
        val packagesWithMdm = (packages + context.packageName).distinct()
        devicePolicyManager.setLockTaskPackages(adminComponent, packagesWithMdm.toTypedArray())
    }

    /**
     * Get packages currently allowed in lock task mode
     */
    fun getLockTaskPackages(): List<String> {
        return try {
            devicePolicyManager.getLockTaskPackages(adminComponent)?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Check if a package is allowed in lock task mode
     */
    fun isPackageAllowedInLockTask(packageName: String): Boolean {
        return devicePolicyManager.isLockTaskPermitted(packageName)
    }

    // ============================================
    // Lock Task Features (Android 9+)
    // ============================================

    /**
     * Set lock task features
     *
     * @param features Combination of DevicePolicyManager.LOCK_TASK_FEATURE_* flags
     */
    fun setLockTaskFeatures(features: Int): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Lock task features require Device Owner" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "Lock task features require Android 9+" }

        devicePolicyManager.setLockTaskFeatures(adminComponent, features)
    }

    /**
     * Get current lock task features
     */
    fun getLockTaskFeatures(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            devicePolicyManager.getLockTaskFeatures(adminComponent)
        } else {
            0
        }
    }

    // ============================================
    // Status Bar Control
    // ============================================

    /**
     * Enable or disable the status bar
     */
    fun setStatusBarDisabled(disabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Status bar control requires Device Owner" }
        devicePolicyManager.setStatusBarDisabled(adminComponent, disabled)
    }

    /**
     * Check if status bar is disabled
     */
    fun isStatusBarDisabled(): Boolean {
        return try {
            // No direct API to check, so we return based on our last set state
            // This would need to be tracked externally for accuracy
            false
        } catch (e: Exception) {
            false
        }
    }

    // ============================================
    // Keyguard Control
    // ============================================

    /**
     * Enable or disable the keyguard (lock screen)
     */
    fun setKeyguardDisabled(disabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Keyguard control requires Device Owner" }
        devicePolicyManager.setKeyguardDisabled(adminComponent, disabled)
    }

    // ============================================
    // Launch Helpers
    // ============================================

    /**
     * Launch an app in lock task mode
     */
    private fun launchInLockTask(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("Could not find launch intent for $packageName")

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        context.startActivity(launchIntent)
    }

    /**
     * Start lock task mode from an Activity
     *
     * Call this from the Activity that should run in lock task mode.
     * The package must be in the lock task packages list.
     */
    fun startLockTaskFromActivity(activity: Activity): Result<Unit> = runCatching {
        require(isPackageAllowedInLockTask(activity.packageName)) {
            "Package ${activity.packageName} is not allowed in lock task mode"
        }
        activity.startLockTask()
    }

    /**
     * Stop lock task mode from an Activity
     */
    fun stopLockTaskFromActivity(activity: Activity): Result<Unit> = runCatching {
        activity.stopLockTask()
    }

    // ============================================
    // Kiosk Status
    // ============================================

    /**
     * Get current kiosk status
     */
    fun getKioskStatus(): KioskStatus {
        return KioskStatus(
            inLockTaskMode = isInLockTaskMode(),
            lockTaskModeState = getLockTaskModeState(),
            allowedPackages = getLockTaskPackages(),
            features = getLockTaskFeatures()
        )
    }
}

/**
 * Current kiosk status
 */
data class KioskStatus(
    val inLockTaskMode: Boolean,
    val lockTaskModeState: Int,
    val allowedPackages: List<String>,
    val features: Int
)

// ============================================
// Activity Extension Functions for Immersive Mode
// ============================================

/**
 * Apply immersive mode to an Activity for kiosk mode.
 *
 * Hides system UI elements (status bar, navigation bar) and makes
 * them only temporarily visible on swipe.
 *
 * Usage in Activity.onCreate():
 * ```kotlin
 * applyKioskImmersiveMode(hideNavigationBar = true, stickyImmersive = true)
 * ```
 */
fun Activity.applyKioskImmersiveMode(
    hideNavigationBar: Boolean = true,
    stickyImmersive: Boolean = true
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Android 11+
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { controller ->
            if (hideNavigationBar) {
                controller.hide(WindowInsets.Type.navigationBars())
            }
            controller.hide(WindowInsets.Type.statusBars())

            if (stickyImmersive) {
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    } else {
        // Android 10 and below
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = buildLegacySystemUiFlags(
            hideNavigationBar,
            stickyImmersive
        )
    }

    // Keep screen on
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/**
 * Exit immersive mode and show system UI
 */
fun Activity.exitKioskImmersiveMode() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(true)
        window.insetsController?.let { controller ->
            controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

@Suppress("DEPRECATION")
private fun buildLegacySystemUiFlags(hideNavigationBar: Boolean, stickyImmersive: Boolean): Int {
    var flags = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

    if (hideNavigationBar) {
        flags = flags or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    if (stickyImmersive) {
        flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    } else {
        flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE
    }

    return flags
}

/**
 * Apply kiosk UI configuration to an Activity.
 *
 * Convenience method that applies all kiosk UI settings from a KioskConfig.
 */
fun Activity.applyKioskUI(config: KioskConfig) {
    if (config.immersiveMode || config.statusBarLocked) {
        applyKioskImmersiveMode(
            hideNavigationBar = config.immersiveMode,
            stickyImmersive = true
        )
    }
}
