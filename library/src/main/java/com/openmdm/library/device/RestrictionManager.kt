package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.UserManager

/**
 * User Restrictions Manager
 *
 * Manages UserManager.DISALLOW_* restrictions with:
 * - Individual restriction control
 * - Bulk restriction from comma-separated strings (Headwind MDM compatibility)
 * - Preset profiles (lockdown, kiosk)
 * - API level validation
 *
 * Usage:
 * ```kotlin
 * val restrictionManager = RestrictionManager.create(context, adminComponent)
 * restrictionManager.setRestriction(UserManager.DISALLOW_INSTALL_APPS, true)
 * restrictionManager.applyRestrictionsFromString("no_install_apps,no_uninstall_apps")
 * restrictionManager.applyLockdownPreset()
 * ```
 */
class RestrictionManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val userManager: UserManager =
        context.getSystemService(Context.USER_SERVICE) as UserManager

    companion object {
        /**
         * Create RestrictionManager with a DeviceAdminReceiver class
         */
        fun <T : android.app.admin.DeviceAdminReceiver> create(
            context: Context,
            adminReceiverClass: Class<T>
        ): RestrictionManager {
            val adminComponent = ComponentName(context, adminReceiverClass)
            return RestrictionManager(context, adminComponent)
        }

        /**
         * Create RestrictionManager with explicit ComponentName
         */
        fun create(context: Context, adminComponent: ComponentName): RestrictionManager {
            return RestrictionManager(context, adminComponent)
        }

        /**
         * Map of short names to full restriction constants.
         * Used for parsing comma-separated restriction strings.
         */
        val RESTRICTION_ALIASES = mapOf(
            // Install/Uninstall
            "no_install_apps" to UserManager.DISALLOW_INSTALL_APPS,
            "no_uninstall_apps" to UserManager.DISALLOW_UNINSTALL_APPS,
            "no_install_unknown_sources" to UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            "no_install_unknown_sources_globally" to UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,

            // Factory Reset & Safe Mode
            "no_factory_reset" to UserManager.DISALLOW_FACTORY_RESET,
            "no_safe_boot" to UserManager.DISALLOW_SAFE_BOOT,

            // Debugging & Development
            "no_debugging_features" to UserManager.DISALLOW_DEBUGGING_FEATURES,

            // Connectivity
            "no_config_wifi" to UserManager.DISALLOW_CONFIG_WIFI,
            "no_change_wifi_state" to UserManager.DISALLOW_CHANGE_WIFI_STATE,
            "no_config_bluetooth" to UserManager.DISALLOW_CONFIG_BLUETOOTH,
            "no_bluetooth" to UserManager.DISALLOW_BLUETOOTH,
            "no_bluetooth_sharing" to UserManager.DISALLOW_BLUETOOTH_SHARING,
            "no_share_location" to UserManager.DISALLOW_SHARE_LOCATION,
            "no_config_location" to UserManager.DISALLOW_CONFIG_LOCATION,
            "no_config_mobile_networks" to UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            "no_config_tethering" to UserManager.DISALLOW_CONFIG_TETHERING,
            "no_airplane_mode" to UserManager.DISALLOW_AIRPLANE_MODE,
            "no_sms" to UserManager.DISALLOW_SMS,
            "no_outgoing_calls" to UserManager.DISALLOW_OUTGOING_CALLS,

            // USB & Storage
            "no_usb_file_transfer" to UserManager.DISALLOW_USB_FILE_TRANSFER,
            "no_mount_physical_media" to UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,

            // Users
            "no_add_user" to UserManager.DISALLOW_ADD_USER,
            "no_remove_user" to UserManager.DISALLOW_REMOVE_USER,
            "no_add_managed_profile" to UserManager.DISALLOW_ADD_MANAGED_PROFILE,
            "no_remove_managed_profile" to UserManager.DISALLOW_REMOVE_MANAGED_PROFILE,
            "no_user_switch" to UserManager.DISALLOW_USER_SWITCH,

            // Settings & Config
            "no_config_date_time" to UserManager.DISALLOW_CONFIG_DATE_TIME,
            "no_config_credentials" to UserManager.DISALLOW_CONFIG_CREDENTIALS,
            "no_config_screen_timeout" to "no_config_screen_timeout",
            "no_config_brightness" to UserManager.DISALLOW_CONFIG_BRIGHTNESS,
            "no_config_locale" to UserManager.DISALLOW_CONFIG_LOCALE,
            "no_config_vpn" to UserManager.DISALLOW_CONFIG_VPN,
            "no_config_private_dns" to UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            "no_ambient_display" to UserManager.DISALLOW_AMBIENT_DISPLAY,

            // Content & Sharing
            "no_share_into_managed_profile" to UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE,
            "no_unified_password" to UserManager.DISALLOW_UNIFIED_PASSWORD,
            "no_cross_profile_copy_paste" to UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE,
            "no_data_roaming" to UserManager.DISALLOW_DATA_ROAMING,

            // Apps & Features
            "no_modify_accounts" to UserManager.DISALLOW_MODIFY_ACCOUNTS,
            "no_set_wallpaper" to UserManager.DISALLOW_SET_WALLPAPER,
            "no_fun" to UserManager.DISALLOW_FUN,
            "no_create_windows" to UserManager.DISALLOW_CREATE_WINDOWS,
            "no_system_error_dialogs" to UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS,
            "no_autofill" to UserManager.DISALLOW_AUTOFILL,
            "no_content_capture" to UserManager.DISALLOW_CONTENT_CAPTURE,
            "no_content_suggestions" to UserManager.DISALLOW_CONTENT_SUGGESTIONS,
            "no_printing" to UserManager.DISALLOW_PRINTING,

            // Camera & Media
            "no_camera" to "no_camera", // Uses setCameraDisabled instead of user restriction
            "no_unmute_microphone" to UserManager.DISALLOW_UNMUTE_MICROPHONE,
            "no_adjust_volume" to UserManager.DISALLOW_ADJUST_VOLUME,

            // Network
            "no_network_reset" to UserManager.DISALLOW_NETWORK_RESET,
            "no_outgoing_beam" to UserManager.DISALLOW_OUTGOING_BEAM
        )

        /**
         * Restrictions that require specific minimum API levels
         */
        val API_REQUIREMENTS = mapOf(
            UserManager.DISALLOW_BLUETOOTH to Build.VERSION_CODES.O,
            UserManager.DISALLOW_BLUETOOTH_SHARING to Build.VERSION_CODES.O,
            UserManager.DISALLOW_CONFIG_LOCATION to Build.VERSION_CODES.P,
            UserManager.DISALLOW_AIRPLANE_MODE to Build.VERSION_CODES.P,
            UserManager.DISALLOW_AMBIENT_DISPLAY to Build.VERSION_CODES.P,
            UserManager.DISALLOW_CONFIG_BRIGHTNESS to Build.VERSION_CODES.P,
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS to Build.VERSION_CODES.Q,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY to Build.VERSION_CODES.Q,
            UserManager.DISALLOW_CONTENT_CAPTURE to Build.VERSION_CODES.Q,
            UserManager.DISALLOW_CONTENT_SUGGESTIONS to Build.VERSION_CODES.Q,
            UserManager.DISALLOW_ADD_MANAGED_PROFILE to Build.VERSION_CODES.LOLLIPOP,
            UserManager.DISALLOW_REMOVE_MANAGED_PROFILE to Build.VERSION_CODES.LOLLIPOP,
            UserManager.DISALLOW_USER_SWITCH to Build.VERSION_CODES.P,
            UserManager.DISALLOW_CHANGE_WIFI_STATE to Build.VERSION_CODES.Q,
            UserManager.DISALLOW_CONFIG_LOCALE to Build.VERSION_CODES.P
        )
    }

    // ============================================
    // Status Checks
    // ============================================

    private fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(context.packageName)

    /**
     * Check if restriction is supported on current API level
     */
    fun isRestrictionSupported(restriction: String): Boolean {
        val requiredApi = API_REQUIREMENTS[restriction] ?: Build.VERSION_CODES.LOLLIPOP
        return Build.VERSION.SDK_INT >= requiredApi
    }

    // ============================================
    // Individual Restriction Control
    // ============================================

    /**
     * Set a user restriction.
     *
     * @param restriction The restriction key (UserManager.DISALLOW_*)
     * @param enabled True to add restriction, false to clear it
     */
    fun setRestriction(restriction: String, enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "User restrictions require Device Owner" }

        // Handle special cases
        if (restriction == "no_camera") {
            devicePolicyManager.setCameraDisabled(adminComponent, enabled)
            return@runCatching
        }

        // Resolve alias if needed
        val actualRestriction = RESTRICTION_ALIASES[restriction] ?: restriction

        // Check API level
        if (!isRestrictionSupported(actualRestriction)) {
            throw UnsupportedOperationException(
                "Restriction $actualRestriction requires API ${API_REQUIREMENTS[actualRestriction]}"
            )
        }

        if (enabled) {
            devicePolicyManager.addUserRestriction(adminComponent, actualRestriction)
        } else {
            devicePolicyManager.clearUserRestriction(adminComponent, actualRestriction)
        }
    }

    /**
     * Check if a restriction is currently active
     */
    fun hasRestriction(restriction: String): Boolean {
        val actualRestriction = RESTRICTION_ALIASES[restriction] ?: restriction
        return userManager.hasUserRestriction(actualRestriction)
    }

    /**
     * Get all active restrictions
     */
    fun getActiveRestrictions(): List<String> {
        val restrictions = userManager.userRestrictions ?: Bundle()
        return restrictions.keySet().filter { restrictions.getBoolean(it) }
    }

    // ============================================
    // Bulk Restriction Control
    // ============================================

    /**
     * Apply restrictions from a comma-separated string.
     *
     * Supports both full restriction names (DISALLOW_INSTALL_APPS)
     * and short aliases (no_install_apps).
     *
     * @param csv Comma-separated restriction names
     * @return Map of restriction to success status
     */
    fun applyRestrictionsFromString(csv: String): Result<Map<String, Boolean>> = runCatching {
        require(isDeviceOwner()) { "User restrictions require Device Owner" }

        val restrictions = csv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        restrictions.associate { restriction ->
            restriction to setRestriction(restriction, true).isSuccess
        }
    }

    /**
     * Apply multiple restrictions at once
     *
     * @param restrictions Map of restriction name to enabled state
     * @return Map of restriction to success status
     */
    fun applyRestrictions(restrictions: Map<String, Boolean>): Map<String, Boolean> {
        return restrictions.mapValues { (restriction, enabled) ->
            setRestriction(restriction, enabled).isSuccess
        }
    }

    /**
     * Clear all restrictions
     */
    fun clearAllRestrictions(): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Clearing restrictions requires Device Owner" }

        getActiveRestrictions().forEach { restriction ->
            runCatching {
                devicePolicyManager.clearUserRestriction(adminComponent, restriction)
            }
        }

        // Also re-enable camera
        devicePolicyManager.setCameraDisabled(adminComponent, false)
    }

    // ============================================
    // Preset Profiles
    // ============================================

    /**
     * Apply lockdown preset - maximum restrictions for fully managed devices.
     *
     * Restricts:
     * - App installation/uninstallation
     * - Factory reset
     * - Debugging
     * - USB file transfer
     * - Safe boot
     * - User modifications
     */
    fun applyLockdownPreset(): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Lockdown preset requires Device Owner" }

        val lockdownRestrictions = listOf(
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA
        )

        lockdownRestrictions.forEach { restriction ->
            if (isRestrictionSupported(restriction)) {
                devicePolicyManager.addUserRestriction(adminComponent, restriction)
            }
        }
    }

    /**
     * Apply kiosk preset - restrictions suitable for single-app kiosk mode.
     *
     * In addition to lockdown restrictions, also restricts:
     * - Configuration changes (WiFi, Bluetooth, etc.)
     * - Status bar access
     * - System settings
     */
    fun applyKioskPreset(): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Kiosk preset requires Device Owner" }

        // First apply lockdown
        applyLockdownPreset()

        // Add kiosk-specific restrictions
        val kioskRestrictions = listOf(
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_CONFIG_BLUETOOTH,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_SET_WALLPAPER,
            UserManager.DISALLOW_CREATE_WINDOWS,
            UserManager.DISALLOW_ADJUST_VOLUME
        )

        kioskRestrictions.forEach { restriction ->
            if (isRestrictionSupported(restriction)) {
                devicePolicyManager.addUserRestriction(adminComponent, restriction)
            }
        }

        // Add API-level specific restrictions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION)
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_CHANGE_WIFI_STATE)
        }
    }

    /**
     * Apply minimal preset - only critical restrictions.
     *
     * Restricts only:
     * - Factory reset
     * - Safe boot
     * - USB file transfer
     */
    fun applyMinimalPreset(): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Minimal preset requires Device Owner" }

        val minimalRestrictions = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_USB_FILE_TRANSFER
        )

        minimalRestrictions.forEach { restriction ->
            if (isRestrictionSupported(restriction)) {
                devicePolicyManager.addUserRestriction(adminComponent, restriction)
            }
        }
    }

    // ============================================
    // Restriction Status
    // ============================================

    /**
     * Get current restriction status
     */
    fun getRestrictionStatus(): RestrictionStatus {
        return RestrictionStatus(
            activeRestrictions = getActiveRestrictions(),
            cameraDisabled = devicePolicyManager.getCameraDisabled(adminComponent),
            isDeviceOwner = isDeviceOwner()
        )
    }
}

/**
 * Current restriction status
 */
data class RestrictionStatus(
    val activeRestrictions: List<String>,
    val cameraDisabled: Boolean,
    val isDeviceOwner: Boolean
)
