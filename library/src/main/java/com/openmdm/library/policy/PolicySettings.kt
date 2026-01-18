package com.openmdm.library.policy

/**
 * Typed policy settings for MDM configuration.
 *
 * This data class provides type-safe access to all policy settings
 * that can be configured from the MDM server.
 */
data class PolicySettings(
    // ============================================
    // General
    // ============================================
    val policyId: String? = null,
    val policyName: String? = null,
    val policyVersion: String? = null,
    val heartbeatInterval: Int = 60, // seconds

    // ============================================
    // Kiosk Mode
    // ============================================
    val kioskMode: Boolean = false,
    val mainApp: String? = null,
    val kioskPackages: List<String> = emptyList(),
    val kioskHome: Boolean = true,
    val kioskRecents: Boolean = false,
    val kioskNotifications: Boolean = false,
    val kioskSystemInfo: Boolean = false,
    val kioskGlobalActions: Boolean = false,
    val kioskKeyguard: Boolean = false,
    val lockStatusBar: Boolean = false,
    val immersiveMode: Boolean = false,

    // ============================================
    // Hardware Controls
    // ============================================
    val wifiEnabled: Boolean? = null,
    val bluetoothEnabled: Boolean? = null,
    val gpsEnabled: Boolean? = null,
    val usbEnabled: Boolean? = null,
    val mobileDataEnabled: Boolean? = null,
    val nfcEnabled: Boolean? = null,
    val airplaneModeEnabled: Boolean? = null,
    val hardwareEnforcementInterval: Int = 30, // seconds

    // ============================================
    // Screen Settings
    // ============================================
    val screenshotDisabled: Boolean = false,
    val screenTimeoutSeconds: Int? = null,
    val brightnessLevel: Int? = null, // 0-255
    val autoBrightness: Boolean? = null,
    val keepScreenOn: Boolean = false,

    // ============================================
    // User Restrictions
    // ============================================
    val restrictions: List<String> = emptyList(),
    val disallowInstallApps: Boolean = false,
    val disallowUninstallApps: Boolean = false,
    val disallowFactoryReset: Boolean = false,
    val disallowSafeMode: Boolean = false,
    val disallowDebugging: Boolean = false,
    val disallowConfigWifi: Boolean = false,
    val disallowConfigBluetooth: Boolean = false,
    val disallowConfigDate: Boolean = false,
    val disallowAddUser: Boolean = false,
    val disallowRemoveUser: Boolean = false,
    val disallowUsbFileTransfer: Boolean = false,
    val disallowMountPhysicalMedia: Boolean = false,
    val disallowOutgoingCalls: Boolean = false,
    val disallowSms: Boolean = false,
    val disallowShare: Boolean = false,
    val disallowCreateWindows: Boolean = false,
    val disallowCamera: Boolean = false,

    // ============================================
    // Network Configuration
    // ============================================
    val wifiNetworks: List<WifiNetworkConfig> = emptyList(),
    val vpnConfig: VpnConfig? = null,
    val proxyConfig: ProxyConfig? = null,

    // ============================================
    // Password Policy
    // ============================================
    val passwordQuality: Int? = null,
    val passwordMinLength: Int? = null,
    val passwordMinLetters: Int? = null,
    val passwordMinNumeric: Int? = null,
    val passwordMinSymbols: Int? = null,
    val passwordMinUpperCase: Int? = null,
    val passwordMinLowerCase: Int? = null,
    val passwordExpirationDays: Int? = null,
    val passwordHistoryLength: Int? = null,
    val maxFailedPasswordAttempts: Int? = null,

    // ============================================
    // App Management
    // ============================================
    val allowedApps: List<String> = emptyList(),
    val blockedApps: List<String> = emptyList(),
    val installedApps: List<AppInstallConfig> = emptyList(),
    val autoGrantPermissions: Boolean = true,
    val defaultBrowserPackage: String? = null,
    val defaultDialerPackage: String? = null,
    val defaultLauncherPackage: String? = null,

    // ============================================
    // File Deployment
    // ============================================
    val fileDeployments: List<FileDeploymentConfig> = emptyList(),

    // ============================================
    // Compliance
    // ============================================
    val encryptionRequired: Boolean = false,
    val screenLockRequired: Boolean = false,
    val minimumOsVersion: String? = null,
    val maximumOsVersion: String? = null,
    val blockedOsVersions: List<String> = emptyList(),

    // ============================================
    // Logging & Telemetry
    // ============================================
    val logLevel: String = "INFO",
    val enableTelemetry: Boolean = true,
    val reportInstalledApps: Boolean = true,
    val reportLocation: Boolean = false,
    val locationInterval: Int = 300, // seconds

    // ============================================
    // Custom Settings
    // ============================================
    val customSettings: Map<String, Any?> = emptyMap()
)

/**
 * WiFi network configuration for provisioning
 */
data class WifiNetworkConfig(
    val ssid: String,
    val password: String? = null,
    val securityType: WifiSecurityType = WifiSecurityType.WPA2,
    val hidden: Boolean = false,
    val autoConnect: Boolean = true,
    val priority: Int = 0,
    val eapConfig: EapConfig? = null
)

enum class WifiSecurityType {
    OPEN,
    WEP,
    WPA,
    WPA2,
    WPA3,
    WPA2_ENTERPRISE,
    WPA3_ENTERPRISE
}

/**
 * EAP configuration for enterprise WiFi
 */
data class EapConfig(
    val method: String, // PEAP, TLS, TTLS, PWD, SIM, AKA
    val phase2Method: String? = null, // MSCHAPV2, GTC, etc.
    val identity: String? = null,
    val anonymousIdentity: String? = null,
    val caCertificate: String? = null,
    val clientCertificate: String? = null,
    val clientKey: String? = null
)

/**
 * VPN configuration
 */
data class VpnConfig(
    val name: String,
    val type: String, // PPTP, L2TP, IPSec, OpenVPN, WireGuard
    val server: String,
    val username: String? = null,
    val password: String? = null,
    val certificate: String? = null,
    val mtu: Int? = null,
    val dns: List<String> = emptyList()
)

/**
 * Proxy configuration
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
    val excludeList: List<String> = emptyList(),
    val pacUrl: String? = null,
    val username: String? = null,
    val password: String? = null
)

/**
 * App installation configuration
 */
data class AppInstallConfig(
    val packageName: String,
    val name: String? = null,
    val version: String? = null,
    val url: String? = null,
    val hash: String? = null,
    val runAfterInstall: Boolean = false,
    val runAtBoot: Boolean = false,
    val showIcon: Boolean = true,
    val grantPermissions: List<String> = emptyList(),
    val whitelistBattery: Boolean = true
)

/**
 * File deployment configuration
 */
data class FileDeploymentConfig(
    val url: String,
    val path: String, // destination path with prefix: internal://, external://, cache://
    val hash: String? = null,
    val overwrite: Boolean = true
)

/**
 * Hardware control policy subset
 */
data class HardwarePolicy(
    val wifiEnabled: Boolean? = null,
    val bluetoothEnabled: Boolean? = null,
    val gpsEnabled: Boolean? = null,
    val usbEnabled: Boolean? = null,
    val mobileDataEnabled: Boolean? = null,
    val nfcEnabled: Boolean? = null,
    val enforcementInterval: Int = 30
) {
    companion object {
        fun fromPolicySettings(settings: PolicySettings): HardwarePolicy {
            return HardwarePolicy(
                wifiEnabled = settings.wifiEnabled,
                bluetoothEnabled = settings.bluetoothEnabled,
                gpsEnabled = settings.gpsEnabled,
                usbEnabled = settings.usbEnabled,
                mobileDataEnabled = settings.mobileDataEnabled,
                nfcEnabled = settings.nfcEnabled,
                enforcementInterval = settings.hardwareEnforcementInterval
            )
        }
    }
}

/**
 * Kiosk mode configuration subset
 */
data class KioskConfig(
    val enabled: Boolean = false,
    val mainApp: String? = null,
    val allowedPackages: List<String> = emptyList(),
    val homeEnabled: Boolean = true,
    val recentsEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val systemInfoEnabled: Boolean = false,
    val globalActionsEnabled: Boolean = false,
    val keyguardEnabled: Boolean = false,
    val statusBarLocked: Boolean = false,
    val immersiveMode: Boolean = false
) {
    companion object {
        fun fromPolicySettings(settings: PolicySettings): KioskConfig {
            return KioskConfig(
                enabled = settings.kioskMode,
                mainApp = settings.mainApp,
                allowedPackages = settings.kioskPackages,
                homeEnabled = settings.kioskHome,
                recentsEnabled = settings.kioskRecents,
                notificationsEnabled = settings.kioskNotifications,
                systemInfoEnabled = settings.kioskSystemInfo,
                globalActionsEnabled = settings.kioskGlobalActions,
                keyguardEnabled = settings.kioskKeyguard,
                statusBarLocked = settings.lockStatusBar,
                immersiveMode = settings.immersiveMode
            )
        }
    }
}
