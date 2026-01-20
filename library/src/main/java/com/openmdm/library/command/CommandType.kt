package com.openmdm.library.command

/**
 * Type-safe sealed class hierarchy for MDM commands.
 *
 * Each command type is strongly typed with its required parameters,
 * providing compile-time safety when processing commands.
 */
sealed class CommandType {
    abstract val id: String

    // ============================================
    // Device Control Commands
    // ============================================

    data class Sync(override val id: String) : CommandType()

    data class Reboot(override val id: String) : CommandType()

    data class Lock(
        override val id: String,
        val message: String? = null
    ) : CommandType()

    data class Unlock(override val id: String) : CommandType()

    data class Wipe(
        override val id: String,
        val preserveData: Boolean = false,
        val wipeExternalStorage: Boolean = false
    ) : CommandType()

    data class FactoryReset(override val id: String) : CommandType()

    // ============================================
    // App Management Commands
    // ============================================

    data class InstallApp(
        override val id: String,
        val packageName: String,
        val url: String,
        val version: String? = null,
        val hash: String? = null,
        val autoGrantPermissions: Boolean = true,
        val whitelistBattery: Boolean = true,
        val runAfterInstall: Boolean = false
    ) : CommandType()

    data class UninstallApp(
        override val id: String,
        val packageName: String
    ) : CommandType()

    data class UpdateApp(
        override val id: String,
        val packageName: String,
        val url: String,
        val version: String? = null,
        val hash: String? = null
    ) : CommandType()

    data class RunApp(
        override val id: String,
        val packageName: String,
        val activity: String? = null,
        val extras: Map<String, String> = emptyMap()
    ) : CommandType()

    data class ClearAppData(
        override val id: String,
        val packageName: String
    ) : CommandType()

    data class ClearAppCache(
        override val id: String,
        val packageName: String
    ) : CommandType()

    data class SetAppVisibility(
        override val id: String,
        val packageName: String,
        val visible: Boolean
    ) : CommandType()

    data class HideApp(
        override val id: String,
        val packageName: String
    ) : CommandType()

    data class ShowApp(
        override val id: String,
        val packageName: String
    ) : CommandType()

    data class SuspendApp(
        override val id: String,
        val packageName: String,
        val suspended: Boolean
    ) : CommandType()

    // ============================================
    // Permission Commands
    // ============================================

    data class GrantPermissions(
        override val id: String,
        val packageName: String,
        val permissions: List<String> = emptyList() // empty = grant common permissions
    ) : CommandType()

    data class WhitelistBattery(
        override val id: String,
        val packageName: String
    ) : CommandType()

    // ============================================
    // Kiosk Mode Commands
    // ============================================

    data class EnterKiosk(
        override val id: String,
        val packageName: String,
        val allowedPackages: List<String> = emptyList(),
        val lockStatusBar: Boolean = false,
        val hideNavigationBar: Boolean = false,
        val enableHomeButton: Boolean = true
    ) : CommandType()

    data class ExitKiosk(override val id: String) : CommandType()

    // ============================================
    // Hardware Control Commands
    // ============================================

    data class SetWifi(
        override val id: String,
        val enabled: Boolean
    ) : CommandType()

    data class SetBluetooth(
        override val id: String,
        val enabled: Boolean
    ) : CommandType()

    data class SetGps(
        override val id: String,
        val enabled: Boolean
    ) : CommandType()

    data class SetUsb(
        override val id: String,
        val enabled: Boolean
    ) : CommandType()

    data class SetMobileData(
        override val id: String,
        val enabled: Boolean
    ) : CommandType()

    data class SetNfc(
        override val id: String,
        val enabled: Boolean
    ) : CommandType()

    // ============================================
    // Screen Commands
    // ============================================

    data class SetScreenshot(
        override val id: String,
        val disabled: Boolean
    ) : CommandType()

    data class SetBrightness(
        override val id: String,
        val level: Int // 0-255
    ) : CommandType()

    data class SetScreenTimeout(
        override val id: String,
        val timeoutSeconds: Int
    ) : CommandType()

    // ============================================
    // Network Commands
    // ============================================

    data class ConfigureWifi(
        override val id: String,
        val ssid: String,
        val password: String? = null,
        val securityType: String = "WPA2",
        val hidden: Boolean = false
    ) : CommandType()

    data class RemoveWifi(
        override val id: String,
        val ssid: String
    ) : CommandType()

    // ============================================
    // System Commands
    // ============================================

    data class Shell(
        override val id: String,
        val command: String,
        val timeout: Int = 30000 // ms
    ) : CommandType()

    data class SetVolume(
        override val id: String,
        val level: Int,
        val streamType: String = "music" // music, ring, notification, alarm, system
    ) : CommandType()

    data class GetLocation(override val id: String) : CommandType()

    data class Screenshot(override val id: String) : CommandType()

    data class SetTimeZone(
        override val id: String,
        val timezone: String
    ) : CommandType()

    data class SetAdb(
        override val id: String,
        val enabled: Boolean
    ) : CommandType()

    // ============================================
    // Restriction Commands
    // ============================================

    data class SetRestriction(
        override val id: String,
        val restriction: String,
        val enabled: Boolean
    ) : CommandType()

    data class SetRestrictions(
        override val id: String,
        val restrictions: Map<String, Boolean>
    ) : CommandType()

    // ============================================
    // File Commands
    // ============================================

    data class DeployFile(
        override val id: String,
        val url: String,
        val path: String,
        val hash: String? = null,
        val overwrite: Boolean = true
    ) : CommandType()

    data class DeleteFile(
        override val id: String,
        val path: String
    ) : CommandType()

    // ============================================
    // Policy Commands
    // ============================================

    data class SetPolicy(
        override val id: String,
        val policy: Map<String, Any?>
    ) : CommandType()

    // ============================================
    // Notification Commands
    // ============================================

    data class SendNotification(
        override val id: String,
        val title: String,
        val body: String,
        val priority: String = "HIGH"
    ) : CommandType()

    // ============================================
    // Custom/Unknown Commands
    // ============================================

    data class Custom(
        override val id: String,
        val customType: String,
        val payload: Map<String, Any?> = emptyMap()
    ) : CommandType()

    data class Unknown(
        override val id: String,
        val type: String,
        val payload: Map<String, Any?>? = null
    ) : CommandType()

    companion object {
        /**
         * Parse a command from its type string and payload map
         */
        @Suppress("UNCHECKED_CAST")
        fun parse(id: String, type: String, payload: Map<String, Any?>?): CommandType {
            return when (type) {
                // Device Control
                "sync" -> Sync(id)
                "reboot" -> Reboot(id)
                "lock" -> Lock(id, payload?.get("message") as? String)
                "unlock" -> Unlock(id)
                "wipe" -> Wipe(
                    id = id,
                    preserveData = payload?.get("preserveData") as? Boolean ?: false,
                    wipeExternalStorage = payload?.get("wipeExternalStorage") as? Boolean ?: false
                )
                "factoryReset" -> FactoryReset(id)

                // App Management
                "installApp" -> InstallApp(
                    id = id,
                    packageName = payload?.get("packageName") as? String ?: "",
                    url = payload?.get("url") as? String ?: "",
                    version = payload?.get("version") as? String,
                    hash = payload?.get("hash") as? String,
                    autoGrantPermissions = payload?.get("autoGrantPermissions") as? Boolean ?: true,
                    whitelistBattery = payload?.get("whitelistBattery") as? Boolean ?: true,
                    runAfterInstall = payload?.get("runAfterInstall") as? Boolean ?: false
                )
                "uninstallApp" -> UninstallApp(id, payload?.get("packageName") as? String ?: "")
                "updateApp" -> UpdateApp(
                    id = id,
                    packageName = payload?.get("packageName") as? String ?: "",
                    url = payload?.get("url") as? String ?: "",
                    version = payload?.get("version") as? String,
                    hash = payload?.get("hash") as? String
                )
                "runApp" -> RunApp(
                    id = id,
                    packageName = payload?.get("packageName") as? String ?: "",
                    activity = payload?.get("activity") as? String,
                    extras = (payload?.get("extras") as? Map<String, String>) ?: emptyMap()
                )
                "clearAppData" -> ClearAppData(id, payload?.get("packageName") as? String ?: "")
                "clearAppCache" -> ClearAppCache(id, payload?.get("packageName") as? String ?: "")
                "setAppVisibility" -> SetAppVisibility(
                    id = id,
                    packageName = payload?.get("packageName") as? String ?: "",
                    visible = payload?.get("visible") as? Boolean ?: true
                )
                "hideApp" -> HideApp(id, payload?.get("packageName") as? String ?: "")
                "showApp" -> ShowApp(id, payload?.get("packageName") as? String ?: "")
                "suspendApp" -> SuspendApp(
                    id = id,
                    packageName = payload?.get("packageName") as? String ?: "",
                    suspended = payload?.get("suspended") as? Boolean ?: true
                )

                // Permissions
                "grantPermissions" -> GrantPermissions(
                    id = id,
                    packageName = payload?.get("packageName") as? String ?: "",
                    permissions = (payload?.get("permissions") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                )
                "whitelistBattery" -> WhitelistBattery(id, payload?.get("packageName") as? String ?: "")

                // Kiosk
                "enterKiosk" -> EnterKiosk(
                    id = id,
                    packageName = payload?.get("packageName") as? String
                        ?: payload?.get("mainApp") as? String ?: "",
                    allowedPackages = (payload?.get("allowedPackages") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    lockStatusBar = payload?.get("lockStatusBar") as? Boolean ?: false,
                    hideNavigationBar = payload?.get("hideNavigationBar") as? Boolean ?: false,
                    enableHomeButton = payload?.get("enableHomeButton") as? Boolean ?: true
                )
                "exitKiosk" -> ExitKiosk(id)

                // Hardware
                "setWifi" -> SetWifi(id, payload?.get("enabled") as? Boolean ?: true)
                "setBluetooth" -> SetBluetooth(id, payload?.get("enabled") as? Boolean ?: true)
                "setGps", "setLocation" -> SetGps(id, payload?.get("enabled") as? Boolean ?: true)
                "setUsb" -> SetUsb(id, payload?.get("enabled") as? Boolean ?: true)
                "setMobileData" -> SetMobileData(id, payload?.get("enabled") as? Boolean ?: true)
                "setNfc" -> SetNfc(id, payload?.get("enabled") as? Boolean ?: true)

                // Screen
                "setScreenshot" -> SetScreenshot(id, payload?.get("disabled") as? Boolean ?: true)
                "setBrightness" -> SetBrightness(id, (payload?.get("level") as? Number)?.toInt() ?: 128)
                "setScreenTimeout" -> SetScreenTimeout(id, (payload?.get("timeoutSeconds") as? Number)?.toInt() ?: 60)

                // Network
                "configureWifi", "setWifiNetwork" -> ConfigureWifi(
                    id = id,
                    ssid = payload?.get("ssid") as? String ?: "",
                    password = payload?.get("password") as? String,
                    securityType = payload?.get("securityType") as? String ?: "WPA2",
                    hidden = payload?.get("hidden") as? Boolean ?: false
                )
                "removeWifi" -> RemoveWifi(id, payload?.get("ssid") as? String ?: "")

                // System
                "shell" -> Shell(
                    id = id,
                    command = payload?.get("command") as? String ?: "",
                    timeout = (payload?.get("timeout") as? Number)?.toInt() ?: 30000
                )
                "setVolume" -> SetVolume(
                    id = id,
                    level = (payload?.get("level") as? Number)?.toInt() ?: 50,
                    streamType = payload?.get("streamType") as? String ?: "music"
                )
                "getLocation" -> GetLocation(id)
                "screenshot" -> Screenshot(id)
                "setTimeZone", "setTimezone" -> SetTimeZone(id, payload?.get("timezone") as? String ?: "")
                "setAdb", "enableAdb" -> SetAdb(id, payload?.get("enabled") as? Boolean ?: true)

                // Restrictions
                "setRestriction" -> SetRestriction(
                    id = id,
                    restriction = payload?.get("restriction") as? String ?: "",
                    enabled = payload?.get("enabled") as? Boolean ?: true
                )
                "setRestrictions" -> SetRestrictions(
                    id = id,
                    restrictions = (payload?.get("restrictions") as? Map<String, Boolean>) ?: emptyMap()
                )

                // Files
                "deployFile" -> DeployFile(
                    id = id,
                    url = payload?.get("url") as? String ?: "",
                    path = payload?.get("path") as? String ?: "",
                    hash = payload?.get("hash") as? String,
                    overwrite = payload?.get("overwrite") as? Boolean ?: true
                )
                "deleteFile" -> DeleteFile(id, payload?.get("path") as? String ?: "")

                // Policy
                "setPolicy" -> SetPolicy(id, (payload?.get("policy") as? Map<String, Any?>) ?: payload ?: emptyMap())

                // Notification
                "sendNotification" -> SendNotification(
                    id = id,
                    title = payload?.get("title") as? String ?: "MDM",
                    body = payload?.get("body") as? String ?: "",
                    priority = payload?.get("priority") as? String ?: "HIGH"
                )

                // Custom
                "custom" -> Custom(
                    id = id,
                    customType = payload?.get("customType") as? String ?: "unknown",
                    payload = payload ?: emptyMap()
                )

                // Unknown
                else -> Unknown(id, type, payload)
            }
        }
    }
}

/**
 * Result of command execution
 */
data class CommandResult(
    val success: Boolean,
    val message: String? = null,
    val data: Any? = null
) {
    companion object {
        fun success(message: String? = null, data: Any? = null) = CommandResult(true, message, data)
        fun failure(message: String) = CommandResult(false, message)
    }
}
