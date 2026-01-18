package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.view.WindowManager

/**
 * Screen Control Manager
 *
 * Manages screen-related settings including screenshot control,
 * screen timeout, and brightness.
 *
 * Usage:
 * ```kotlin
 * val screenManager = ScreenManager.create(context, adminComponent)
 * screenManager.setScreenshotDisabled(true)
 * screenManager.setScreenTimeout(300)
 * screenManager.setBrightness(128)
 * ```
 */
class ScreenManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val contentResolver: ContentResolver = context.contentResolver

    companion object {
        // Common timeout presets in seconds
        const val TIMEOUT_15_SECONDS = 15
        const val TIMEOUT_30_SECONDS = 30
        const val TIMEOUT_1_MINUTE = 60
        const val TIMEOUT_2_MINUTES = 120
        const val TIMEOUT_5_MINUTES = 300
        const val TIMEOUT_10_MINUTES = 600
        const val TIMEOUT_30_MINUTES = 1800
        const val TIMEOUT_NEVER = -1

        // Brightness range
        const val BRIGHTNESS_MIN = 0
        const val BRIGHTNESS_MAX = 255
        const val BRIGHTNESS_AUTO = -1

        /**
         * Create ScreenManager with a DeviceAdminReceiver class
         */
        fun <T : android.app.admin.DeviceAdminReceiver> create(
            context: Context,
            adminReceiverClass: Class<T>
        ): ScreenManager {
            val adminComponent = ComponentName(context, adminReceiverClass)
            return ScreenManager(context, adminComponent)
        }

        /**
         * Create ScreenManager with explicit ComponentName
         */
        fun create(context: Context, adminComponent: ComponentName): ScreenManager {
            return ScreenManager(context, adminComponent)
        }
    }

    // ============================================
    // Status Checks
    // ============================================

    private fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(context.packageName)
    private fun isDeviceAdmin(): Boolean = devicePolicyManager.isAdminActive(adminComponent)

    // ============================================
    // Screenshot Control
    // ============================================

    /**
     * Enable or disable screenshot capture.
     *
     * Uses DevicePolicyManager.setScreenCaptureDisabled which prevents:
     * - User screenshots (power + volume down)
     * - Screen recording
     * - Screen sharing
     * - Media projection
     */
    fun setScreenshotDisabled(disabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner() || isDeviceAdmin()) { "Screenshot control requires Device Admin" }
        devicePolicyManager.setScreenCaptureDisabled(adminComponent, disabled)
    }

    /**
     * Check if screenshot capture is disabled
     */
    fun isScreenshotDisabled(): Boolean {
        return devicePolicyManager.getScreenCaptureDisabled(adminComponent)
    }

    // ============================================
    // Screen Timeout Control
    // ============================================

    /**
     * Set screen timeout in seconds.
     *
     * @param timeoutSeconds Timeout in seconds. Use TIMEOUT_NEVER (-1) for no timeout.
     */
    fun setScreenTimeout(timeoutSeconds: Int): Result<Unit> = runCatching {
        val timeoutMs = if (timeoutSeconds == TIMEOUT_NEVER) {
            Int.MAX_VALUE
        } else {
            timeoutSeconds * 1000
        }

        // Set the screen timeout
        Settings.System.putInt(
            contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            timeoutMs
        )

        // Optionally restrict user from changing timeout
        if (isDeviceOwner()) {
            // DISALLOW_CONFIG_SCREEN_TIMEOUT is available on Android 9+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                devicePolicyManager.addUserRestriction(
                    adminComponent,
                    "no_config_screen_timeout"
                )
            }
        }
    }

    /**
     * Get current screen timeout in seconds
     */
    fun getScreenTimeout(): Int {
        val timeoutMs = Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            60000 // Default 1 minute
        )
        return if (timeoutMs >= Int.MAX_VALUE - 1000) {
            TIMEOUT_NEVER
        } else {
            timeoutMs / 1000
        }
    }

    /**
     * Allow user to change screen timeout again
     */
    fun clearScreenTimeoutRestriction(): Result<Unit> = runCatching {
        if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            devicePolicyManager.clearUserRestriction(
                adminComponent,
                "no_config_screen_timeout"
            )
        }
    }

    // ============================================
    // Brightness Control
    // ============================================

    /**
     * Set screen brightness level.
     *
     * @param level Brightness level 0-255, or BRIGHTNESS_AUTO (-1) for auto brightness
     */
    fun setBrightness(level: Int): Result<Unit> = runCatching {
        if (level == BRIGHTNESS_AUTO) {
            // Enable auto brightness
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
        } else {
            require(level in BRIGHTNESS_MIN..BRIGHTNESS_MAX) { "Brightness must be 0-255" }

            // Disable auto brightness first
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            // Set brightness level
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                level
            )
        }
    }

    /**
     * Get current brightness level (0-255) or BRIGHTNESS_AUTO if auto
     */
    fun getBrightness(): Int {
        val mode = Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )

        return if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            BRIGHTNESS_AUTO
        } else {
            Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
        }
    }

    /**
     * Check if auto brightness is enabled
     */
    fun isAutoBrightnessEnabled(): Boolean {
        val mode = Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }

    // ============================================
    // Keep Screen On
    // ============================================

    /**
     * Set maximum screen timeout (DevicePolicyManager)
     *
     * This sets a maximum inactivity time before the device locks.
     * Note: This is different from screen timeout - this is the lock timeout.
     */
    fun setMaximumTimeToLock(timeoutSeconds: Int): Result<Unit> = runCatching {
        require(isDeviceAdmin()) { "Maximum time to lock requires Device Admin" }

        val timeoutMs = if (timeoutSeconds <= 0) {
            0L // No limit
        } else {
            timeoutSeconds * 1000L
        }

        devicePolicyManager.setMaximumTimeToLock(adminComponent, timeoutMs)
    }

    /**
     * Get maximum time to lock in seconds
     */
    fun getMaximumTimeToLock(): Long {
        return devicePolicyManager.getMaximumTimeToLock(adminComponent) / 1000
    }

    // ============================================
    // Screen Status
    // ============================================

    /**
     * Get current screen settings
     */
    fun getScreenStatus(): ScreenStatus {
        return ScreenStatus(
            screenshotDisabled = isScreenshotDisabled(),
            timeoutSeconds = getScreenTimeout(),
            brightness = getBrightness(),
            autoBrightness = isAutoBrightnessEnabled(),
            maximumTimeToLock = getMaximumTimeToLock()
        )
    }
}

/**
 * Current screen settings status
 */
data class ScreenStatus(
    val screenshotDisabled: Boolean,
    val timeoutSeconds: Int,
    val brightness: Int,
    val autoBrightness: Boolean,
    val maximumTimeToLock: Long
)

/**
 * Extension function to keep screen on for an Activity.
 * Call this from Activity.onCreate()
 */
fun android.app.Activity.keepScreenOn(enabled: Boolean) {
    if (enabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
