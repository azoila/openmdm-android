package com.openmdm.library.policy

/**
 * Maps raw policy Map<String, Any?> from server to typed PolicySettings.
 *
 * Handles type coercion, default values, and backward compatibility
 * with various server formats.
 */
object PolicyMapper {

    /**
     * Convert a raw policy map to typed PolicySettings
     */
    fun fromMap(map: Map<String, Any?>?): PolicySettings {
        if (map == null) return PolicySettings()

        return PolicySettings(
            // General
            policyId = map.getString("id") ?: map.getString("policyId"),
            policyName = map.getString("name") ?: map.getString("policyName"),
            policyVersion = map.getString("version") ?: map.getString("policyVersion"),
            heartbeatInterval = map.getInt("heartbeatInterval") ?: 60,

            // Kiosk Mode
            kioskMode = map.getBoolean("kioskMode") ?: map.getBoolean("kiosk") ?: false,
            mainApp = map.getString("mainApp") ?: map.getString("kioskApp"),
            kioskPackages = map.getStringList("kioskPackages")
                ?: map.getStringList("allowedApps")
                ?: emptyList(),
            kioskHome = map.getBoolean("kioskHome") ?: true,
            kioskRecents = map.getBoolean("kioskRecents") ?: false,
            kioskNotifications = map.getBoolean("kioskNotifications") ?: false,
            kioskSystemInfo = map.getBoolean("kioskSystemInfo") ?: false,
            kioskGlobalActions = map.getBoolean("kioskGlobalActions") ?: false,
            kioskKeyguard = map.getBoolean("kioskKeyguard") ?: false,
            lockStatusBar = map.getBoolean("lockStatusBar")
                ?: map.getBoolean("disableStatusBar")
                ?: false,
            immersiveMode = map.getBoolean("immersiveMode") ?: false,

            // Hardware Controls
            wifiEnabled = map.getBoolean("wifiEnabled") ?: map.getBoolean("wifi"),
            bluetoothEnabled = map.getBoolean("bluetoothEnabled") ?: map.getBoolean("bluetooth"),
            gpsEnabled = map.getBoolean("gpsEnabled") ?: map.getBoolean("gps") ?: map.getBoolean("location"),
            usbEnabled = map.getBoolean("usbEnabled") ?: map.getBoolean("usb"),
            mobileDataEnabled = map.getBoolean("mobileDataEnabled") ?: map.getBoolean("mobileData"),
            nfcEnabled = map.getBoolean("nfcEnabled") ?: map.getBoolean("nfc"),
            airplaneModeEnabled = map.getBoolean("airplaneModeEnabled") ?: map.getBoolean("airplaneMode"),
            hardwareEnforcementInterval = map.getInt("hardwareEnforcementInterval") ?: 30,

            // Screen Settings
            screenshotDisabled = map.getBoolean("screenshotDisabled")
                ?: map.getBoolean("disableScreenshot")
                ?: false,
            screenTimeoutSeconds = map.getInt("screenTimeoutSeconds")
                ?: map.getInt("screenTimeout"),
            brightnessLevel = map.getInt("brightnessLevel") ?: map.getInt("brightness"),
            autoBrightness = map.getBoolean("autoBrightness"),
            keepScreenOn = map.getBoolean("keepScreenOn") ?: false,

            // User Restrictions
            restrictions = map.getStringList("restrictions") ?: parseRestrictionsCsv(map.getString("restrictionsCSV")),
            disallowInstallApps = map.getBoolean("disallowInstallApps") ?: false,
            disallowUninstallApps = map.getBoolean("disallowUninstallApps") ?: false,
            disallowFactoryReset = map.getBoolean("disallowFactoryReset") ?: false,
            disallowSafeMode = map.getBoolean("disallowSafeMode") ?: false,
            disallowDebugging = map.getBoolean("disallowDebugging") ?: false,
            disallowConfigWifi = map.getBoolean("disallowConfigWifi") ?: false,
            disallowConfigBluetooth = map.getBoolean("disallowConfigBluetooth") ?: false,
            disallowConfigDate = map.getBoolean("disallowConfigDate") ?: false,
            disallowAddUser = map.getBoolean("disallowAddUser") ?: false,
            disallowRemoveUser = map.getBoolean("disallowRemoveUser") ?: false,
            disallowUsbFileTransfer = map.getBoolean("disallowUsbFileTransfer") ?: false,
            disallowMountPhysicalMedia = map.getBoolean("disallowMountPhysicalMedia") ?: false,
            disallowOutgoingCalls = map.getBoolean("disallowOutgoingCalls") ?: false,
            disallowSms = map.getBoolean("disallowSms") ?: false,
            disallowShare = map.getBoolean("disallowShare") ?: false,
            disallowCreateWindows = map.getBoolean("disallowCreateWindows") ?: false,
            disallowCamera = map.getBoolean("disallowCamera") ?: false,

            // Network Configuration
            wifiNetworks = parseWifiNetworks(map["wifiNetworks"]),
            vpnConfig = parseVpnConfig(map["vpnConfig"]),
            proxyConfig = parseProxyConfig(map["proxyConfig"]),

            // Password Policy
            passwordQuality = map.getInt("passwordQuality"),
            passwordMinLength = map.getInt("passwordMinLength"),
            passwordMinLetters = map.getInt("passwordMinLetters"),
            passwordMinNumeric = map.getInt("passwordMinNumeric"),
            passwordMinSymbols = map.getInt("passwordMinSymbols"),
            passwordMinUpperCase = map.getInt("passwordMinUpperCase"),
            passwordMinLowerCase = map.getInt("passwordMinLowerCase"),
            passwordExpirationDays = map.getInt("passwordExpirationDays"),
            passwordHistoryLength = map.getInt("passwordHistoryLength"),
            maxFailedPasswordAttempts = map.getInt("maxFailedPasswordAttempts"),

            // App Management
            allowedApps = map.getStringList("allowedApps") ?: emptyList(),
            blockedApps = map.getStringList("blockedApps") ?: emptyList(),
            installedApps = parseAppConfigs(map["applications"] ?: map["apps"]),
            autoGrantPermissions = map.getBoolean("autoGrantPermissions") ?: true,
            defaultBrowserPackage = map.getString("defaultBrowserPackage"),
            defaultDialerPackage = map.getString("defaultDialerPackage"),
            defaultLauncherPackage = map.getString("defaultLauncherPackage"),

            // File Deployment
            fileDeployments = parseFileDeployments(map["fileDeployments"] ?: map["files"]),

            // Compliance
            encryptionRequired = map.getBoolean("encryptionRequired") ?: false,
            screenLockRequired = map.getBoolean("screenLockRequired") ?: false,
            minimumOsVersion = map.getString("minimumOsVersion"),
            maximumOsVersion = map.getString("maximumOsVersion"),
            blockedOsVersions = map.getStringList("blockedOsVersions") ?: emptyList(),

            // Logging & Telemetry
            logLevel = map.getString("logLevel") ?: "INFO",
            enableTelemetry = map.getBoolean("enableTelemetry") ?: true,
            reportInstalledApps = map.getBoolean("reportInstalledApps") ?: true,
            reportLocation = map.getBoolean("reportLocation") ?: false,
            locationInterval = map.getInt("locationInterval") ?: 300,

            // Custom Settings
            customSettings = map.getMap("customSettings") ?: emptyMap()
        )
    }

    /**
     * Convert PolicySettings back to a map for serialization
     */
    fun toMap(settings: PolicySettings): Map<String, Any?> {
        return buildMap {
            // General
            settings.policyId?.let { put("policyId", it) }
            settings.policyName?.let { put("policyName", it) }
            settings.policyVersion?.let { put("policyVersion", it) }
            put("heartbeatInterval", settings.heartbeatInterval)

            // Kiosk Mode
            put("kioskMode", settings.kioskMode)
            settings.mainApp?.let { put("mainApp", it) }
            if (settings.kioskPackages.isNotEmpty()) put("kioskPackages", settings.kioskPackages)
            put("kioskHome", settings.kioskHome)
            put("kioskRecents", settings.kioskRecents)
            put("kioskNotifications", settings.kioskNotifications)
            put("kioskSystemInfo", settings.kioskSystemInfo)
            put("kioskGlobalActions", settings.kioskGlobalActions)
            put("kioskKeyguard", settings.kioskKeyguard)
            put("lockStatusBar", settings.lockStatusBar)
            put("immersiveMode", settings.immersiveMode)

            // Hardware Controls
            settings.wifiEnabled?.let { put("wifiEnabled", it) }
            settings.bluetoothEnabled?.let { put("bluetoothEnabled", it) }
            settings.gpsEnabled?.let { put("gpsEnabled", it) }
            settings.usbEnabled?.let { put("usbEnabled", it) }
            settings.mobileDataEnabled?.let { put("mobileDataEnabled", it) }
            settings.nfcEnabled?.let { put("nfcEnabled", it) }
            settings.airplaneModeEnabled?.let { put("airplaneModeEnabled", it) }
            put("hardwareEnforcementInterval", settings.hardwareEnforcementInterval)

            // Screen Settings
            put("screenshotDisabled", settings.screenshotDisabled)
            settings.screenTimeoutSeconds?.let { put("screenTimeoutSeconds", it) }
            settings.brightnessLevel?.let { put("brightnessLevel", it) }
            settings.autoBrightness?.let { put("autoBrightness", it) }
            put("keepScreenOn", settings.keepScreenOn)

            // User Restrictions
            if (settings.restrictions.isNotEmpty()) put("restrictions", settings.restrictions)
            put("disallowInstallApps", settings.disallowInstallApps)
            put("disallowUninstallApps", settings.disallowUninstallApps)
            put("disallowFactoryReset", settings.disallowFactoryReset)
            put("disallowSafeMode", settings.disallowSafeMode)
            put("disallowDebugging", settings.disallowDebugging)
            put("disallowConfigWifi", settings.disallowConfigWifi)
            put("disallowConfigBluetooth", settings.disallowConfigBluetooth)
            put("disallowConfigDate", settings.disallowConfigDate)
            put("disallowAddUser", settings.disallowAddUser)
            put("disallowRemoveUser", settings.disallowRemoveUser)
            put("disallowUsbFileTransfer", settings.disallowUsbFileTransfer)
            put("disallowMountPhysicalMedia", settings.disallowMountPhysicalMedia)
            put("disallowOutgoingCalls", settings.disallowOutgoingCalls)
            put("disallowSms", settings.disallowSms)
            put("disallowShare", settings.disallowShare)
            put("disallowCreateWindows", settings.disallowCreateWindows)
            put("disallowCamera", settings.disallowCamera)

            // Password Policy
            settings.passwordQuality?.let { put("passwordQuality", it) }
            settings.passwordMinLength?.let { put("passwordMinLength", it) }
            settings.passwordMinLetters?.let { put("passwordMinLetters", it) }
            settings.passwordMinNumeric?.let { put("passwordMinNumeric", it) }
            settings.passwordMinSymbols?.let { put("passwordMinSymbols", it) }
            settings.passwordMinUpperCase?.let { put("passwordMinUpperCase", it) }
            settings.passwordMinLowerCase?.let { put("passwordMinLowerCase", it) }
            settings.passwordExpirationDays?.let { put("passwordExpirationDays", it) }
            settings.passwordHistoryLength?.let { put("passwordHistoryLength", it) }
            settings.maxFailedPasswordAttempts?.let { put("maxFailedPasswordAttempts", it) }

            // App Management
            if (settings.allowedApps.isNotEmpty()) put("allowedApps", settings.allowedApps)
            if (settings.blockedApps.isNotEmpty()) put("blockedApps", settings.blockedApps)
            put("autoGrantPermissions", settings.autoGrantPermissions)
            settings.defaultBrowserPackage?.let { put("defaultBrowserPackage", it) }
            settings.defaultDialerPackage?.let { put("defaultDialerPackage", it) }
            settings.defaultLauncherPackage?.let { put("defaultLauncherPackage", it) }

            // Compliance
            put("encryptionRequired", settings.encryptionRequired)
            put("screenLockRequired", settings.screenLockRequired)
            settings.minimumOsVersion?.let { put("minimumOsVersion", it) }
            settings.maximumOsVersion?.let { put("maximumOsVersion", it) }
            if (settings.blockedOsVersions.isNotEmpty()) put("blockedOsVersions", settings.blockedOsVersions)

            // Logging & Telemetry
            put("logLevel", settings.logLevel)
            put("enableTelemetry", settings.enableTelemetry)
            put("reportInstalledApps", settings.reportInstalledApps)
            put("reportLocation", settings.reportLocation)
            put("locationInterval", settings.locationInterval)

            // Custom Settings
            if (settings.customSettings.isNotEmpty()) put("customSettings", settings.customSettings)
        }
    }

    // ============================================
    // Extension helpers for safe type casting
    // ============================================

    private fun Map<String, Any?>.getString(key: String): String? {
        return this[key] as? String
    }

    private fun Map<String, Any?>.getBoolean(key: String): Boolean? {
        return when (val value = this[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.lowercase() in listOf("true", "1", "yes", "on")
            else -> null
        }
    }

    private fun Map<String, Any?>.getInt(key: String): Int? {
        return when (val value = this[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun Map<String, Any?>.getStringList(key: String): List<String>? {
        return when (val value = this[key]) {
            is List<*> -> value.filterIsInstance<String>()
            is String -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.getMap(key: String): Map<String, Any?>? {
        return this[key] as? Map<String, Any?>
    }

    // ============================================
    // Parsers for complex nested objects
    // ============================================

    private fun parseRestrictionsCsv(csv: String?): List<String> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseWifiNetworks(value: Any?): List<WifiNetworkConfig> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            WifiNetworkConfig(
                ssid = map["ssid"] as? String ?: return@mapNotNull null,
                password = map["password"] as? String,
                securityType = parseSecurityType(map["securityType"] as? String),
                hidden = (map["hidden"] as? Boolean) ?: false,
                autoConnect = (map["autoConnect"] as? Boolean) ?: true,
                priority = (map["priority"] as? Number)?.toInt() ?: 0,
                eapConfig = parseEapConfig(map["eapConfig"])
            )
        }
    }

    private fun parseSecurityType(value: String?): WifiSecurityType {
        return when (value?.uppercase()) {
            "OPEN", "NONE" -> WifiSecurityType.OPEN
            "WEP" -> WifiSecurityType.WEP
            "WPA" -> WifiSecurityType.WPA
            "WPA2", "WPA2_PSK" -> WifiSecurityType.WPA2
            "WPA3", "WPA3_PSK" -> WifiSecurityType.WPA3
            "WPA2_ENTERPRISE", "ENTERPRISE" -> WifiSecurityType.WPA2_ENTERPRISE
            "WPA3_ENTERPRISE" -> WifiSecurityType.WPA3_ENTERPRISE
            else -> WifiSecurityType.WPA2
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEapConfig(value: Any?): EapConfig? {
        val map = value as? Map<String, Any?> ?: return null
        return EapConfig(
            method = map["method"] as? String ?: return null,
            phase2Method = map["phase2Method"] as? String,
            identity = map["identity"] as? String,
            anonymousIdentity = map["anonymousIdentity"] as? String,
            caCertificate = map["caCertificate"] as? String,
            clientCertificate = map["clientCertificate"] as? String,
            clientKey = map["clientKey"] as? String
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseVpnConfig(value: Any?): VpnConfig? {
        val map = value as? Map<String, Any?> ?: return null
        return VpnConfig(
            name = map["name"] as? String ?: return null,
            type = map["type"] as? String ?: return null,
            server = map["server"] as? String ?: return null,
            username = map["username"] as? String,
            password = map["password"] as? String,
            certificate = map["certificate"] as? String,
            mtu = (map["mtu"] as? Number)?.toInt(),
            dns = (map["dns"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseProxyConfig(value: Any?): ProxyConfig? {
        val map = value as? Map<String, Any?> ?: return null
        return ProxyConfig(
            host = map["host"] as? String ?: return null,
            port = (map["port"] as? Number)?.toInt() ?: return null,
            excludeList = (map["excludeList"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            pacUrl = map["pacUrl"] as? String,
            username = map["username"] as? String,
            password = map["password"] as? String
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAppConfigs(value: Any?): List<AppInstallConfig> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            AppInstallConfig(
                packageName = map["packageName"] as? String ?: return@mapNotNull null,
                name = map["name"] as? String,
                version = map["version"] as? String,
                url = map["url"] as? String,
                hash = map["hash"] as? String,
                runAfterInstall = (map["runAfterInstall"] as? Boolean) ?: false,
                runAtBoot = (map["runAtBoot"] as? Boolean) ?: false,
                showIcon = (map["showIcon"] as? Boolean) ?: true,
                grantPermissions = (map["grantPermissions"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                whitelistBattery = (map["whitelistBattery"] as? Boolean) ?: true
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFileDeployments(value: Any?): List<FileDeploymentConfig> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            FileDeploymentConfig(
                url = map["url"] as? String ?: return@mapNotNull null,
                path = map["path"] as? String ?: return@mapNotNull null,
                hash = map["hash"] as? String,
                overwrite = (map["overwrite"] as? Boolean) ?: true
            )
        }
    }
}
