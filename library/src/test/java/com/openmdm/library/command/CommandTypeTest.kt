package com.openmdm.library.command

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [CommandType].
 *
 * Tests the parsing of command type strings and payloads into typed command objects.
 * Ensures type safety and correct parameter extraction for all command types.
 */
class CommandTypeTest {

    // ============================================
    // Device Control Commands
    // ============================================

    @Test
    fun `parse Sync command`() {
        val command = CommandType.parse("cmd-1", "sync", null)

        assertThat(command).isInstanceOf(CommandType.Sync::class.java)
        assertThat(command.id).isEqualTo("cmd-1")
    }

    @Test
    fun `parse Reboot command`() {
        val command = CommandType.parse("cmd-2", "reboot", null)

        assertThat(command).isInstanceOf(CommandType.Reboot::class.java)
        assertThat(command.id).isEqualTo("cmd-2")
    }

    @Test
    fun `parse Lock command with message`() {
        val command = CommandType.parse("cmd-3", "lock", mapOf("message" to "Device locked by admin"))

        assertThat(command).isInstanceOf(CommandType.Lock::class.java)
        val lock = command as CommandType.Lock
        assertThat(lock.id).isEqualTo("cmd-3")
        assertThat(lock.message).isEqualTo("Device locked by admin")
    }

    @Test
    fun `parse Lock command without message`() {
        val command = CommandType.parse("cmd-4", "lock", null)

        assertThat(command).isInstanceOf(CommandType.Lock::class.java)
        val lock = command as CommandType.Lock
        assertThat(lock.message).isNull()
    }

    @Test
    fun `parse Wipe command with options`() {
        val command = CommandType.parse("cmd-5", "wipe", mapOf(
            "preserveData" to true,
            "wipeExternalStorage" to true
        ))

        assertThat(command).isInstanceOf(CommandType.Wipe::class.java)
        val wipe = command as CommandType.Wipe
        assertThat(wipe.preserveData).isTrue()
        assertThat(wipe.wipeExternalStorage).isTrue()
    }

    @Test
    fun `parse Wipe command with defaults`() {
        val command = CommandType.parse("cmd-6", "wipe", null)

        assertThat(command).isInstanceOf(CommandType.Wipe::class.java)
        val wipe = command as CommandType.Wipe
        assertThat(wipe.preserveData).isFalse()
        assertThat(wipe.wipeExternalStorage).isFalse()
    }

    @Test
    fun `parse FactoryReset command`() {
        val command = CommandType.parse("cmd-7", "factoryReset", null)

        assertThat(command).isInstanceOf(CommandType.FactoryReset::class.java)
    }

    // ============================================
    // App Management Commands
    // ============================================

    @Test
    fun `parse InstallApp command with all parameters`() {
        val command = CommandType.parse("cmd-10", "installApp", mapOf(
            "packageName" to "com.example.app",
            "url" to "https://example.com/app.apk",
            "version" to "1.0.0",
            "hash" to "abc123",
            "autoGrantPermissions" to false,
            "whitelistBattery" to false,
            "runAfterInstall" to true
        ))

        assertThat(command).isInstanceOf(CommandType.InstallApp::class.java)
        val install = command as CommandType.InstallApp
        assertThat(install.packageName).isEqualTo("com.example.app")
        assertThat(install.url).isEqualTo("https://example.com/app.apk")
        assertThat(install.version).isEqualTo("1.0.0")
        assertThat(install.hash).isEqualTo("abc123")
        assertThat(install.autoGrantPermissions).isFalse()
        assertThat(install.whitelistBattery).isFalse()
        assertThat(install.runAfterInstall).isTrue()
    }

    @Test
    fun `parse InstallApp command with defaults`() {
        val command = CommandType.parse("cmd-11", "installApp", mapOf(
            "packageName" to "com.example.app",
            "url" to "https://example.com/app.apk"
        ))

        val install = command as CommandType.InstallApp
        assertThat(install.autoGrantPermissions).isTrue()
        assertThat(install.whitelistBattery).isTrue()
        assertThat(install.runAfterInstall).isFalse()
        assertThat(install.version).isNull()
        assertThat(install.hash).isNull()
    }

    @Test
    fun `parse UninstallApp command`() {
        val command = CommandType.parse("cmd-12", "uninstallApp", mapOf(
            "packageName" to "com.example.app"
        ))

        assertThat(command).isInstanceOf(CommandType.UninstallApp::class.java)
        val uninstall = command as CommandType.UninstallApp
        assertThat(uninstall.packageName).isEqualTo("com.example.app")
    }

    @Test
    fun `parse UpdateApp command`() {
        val command = CommandType.parse("cmd-13", "updateApp", mapOf(
            "packageName" to "com.example.app",
            "url" to "https://example.com/app-v2.apk",
            "version" to "2.0.0"
        ))

        assertThat(command).isInstanceOf(CommandType.UpdateApp::class.java)
        val update = command as CommandType.UpdateApp
        assertThat(update.packageName).isEqualTo("com.example.app")
        assertThat(update.url).isEqualTo("https://example.com/app-v2.apk")
        assertThat(update.version).isEqualTo("2.0.0")
    }

    @Test
    fun `parse RunApp command with extras`() {
        val command = CommandType.parse("cmd-14", "runApp", mapOf(
            "packageName" to "com.example.app",
            "activity" to ".MainActivity",
            "extras" to mapOf("key" to "value")
        ))

        assertThat(command).isInstanceOf(CommandType.RunApp::class.java)
        val run = command as CommandType.RunApp
        assertThat(run.packageName).isEqualTo("com.example.app")
        assertThat(run.activity).isEqualTo(".MainActivity")
        assertThat(run.extras).containsEntry("key", "value")
    }

    @Test
    fun `parse ClearAppData command`() {
        val command = CommandType.parse("cmd-15", "clearAppData", mapOf(
            "packageName" to "com.example.app"
        ))

        assertThat(command).isInstanceOf(CommandType.ClearAppData::class.java)
        assertThat((command as CommandType.ClearAppData).packageName).isEqualTo("com.example.app")
    }

    // ============================================
    // Permission Commands
    // ============================================

    @Test
    fun `parse GrantPermissions command with specific permissions`() {
        val command = CommandType.parse("cmd-20", "grantPermissions", mapOf(
            "packageName" to "com.example.app",
            "permissions" to listOf("android.permission.CAMERA", "android.permission.LOCATION")
        ))

        assertThat(command).isInstanceOf(CommandType.GrantPermissions::class.java)
        val grant = command as CommandType.GrantPermissions
        assertThat(grant.packageName).isEqualTo("com.example.app")
        assertThat(grant.permissions).containsExactly(
            "android.permission.CAMERA", "android.permission.LOCATION"
        )
    }

    @Test
    fun `parse GrantPermissions command with empty permissions grants common`() {
        val command = CommandType.parse("cmd-21", "grantPermissions", mapOf(
            "packageName" to "com.example.app"
        ))

        val grant = command as CommandType.GrantPermissions
        assertThat(grant.permissions).isEmpty()
    }

    @Test
    fun `parse WhitelistBattery command`() {
        val command = CommandType.parse("cmd-22", "whitelistBattery", mapOf(
            "packageName" to "com.example.app"
        ))

        assertThat(command).isInstanceOf(CommandType.WhitelistBattery::class.java)
        assertThat((command as CommandType.WhitelistBattery).packageName).isEqualTo("com.example.app")
    }

    // ============================================
    // Kiosk Mode Commands
    // ============================================

    @Test
    fun `parse EnterKiosk command with all options`() {
        val command = CommandType.parse("cmd-30", "enterKiosk", mapOf(
            "packageName" to "com.kiosk.app",
            "allowedPackages" to listOf("com.app1", "com.app2"),
            "lockStatusBar" to true,
            "hideNavigationBar" to true,
            "enableHomeButton" to false
        ))

        assertThat(command).isInstanceOf(CommandType.EnterKiosk::class.java)
        val kiosk = command as CommandType.EnterKiosk
        assertThat(kiosk.packageName).isEqualTo("com.kiosk.app")
        assertThat(kiosk.allowedPackages).containsExactly("com.app1", "com.app2")
        assertThat(kiosk.lockStatusBar).isTrue()
        assertThat(kiosk.hideNavigationBar).isTrue()
        assertThat(kiosk.enableHomeButton).isFalse()
    }

    @Test
    fun `parse EnterKiosk command with mainApp alternative key`() {
        val command = CommandType.parse("cmd-31", "enterKiosk", mapOf(
            "mainApp" to "com.kiosk.app"
        ))

        val kiosk = command as CommandType.EnterKiosk
        assertThat(kiosk.packageName).isEqualTo("com.kiosk.app")
    }

    @Test
    fun `parse ExitKiosk command`() {
        val command = CommandType.parse("cmd-32", "exitKiosk", null)

        assertThat(command).isInstanceOf(CommandType.ExitKiosk::class.java)
    }

    // ============================================
    // Hardware Control Commands
    // ============================================

    @Test
    fun `parse SetWifi command`() {
        val command = CommandType.parse("cmd-40", "setWifi", mapOf("enabled" to false))

        assertThat(command).isInstanceOf(CommandType.SetWifi::class.java)
        assertThat((command as CommandType.SetWifi).enabled).isFalse()
    }

    @Test
    fun `parse SetBluetooth command`() {
        val command = CommandType.parse("cmd-41", "setBluetooth", mapOf("enabled" to true))

        assertThat(command).isInstanceOf(CommandType.SetBluetooth::class.java)
        assertThat((command as CommandType.SetBluetooth).enabled).isTrue()
    }

    @Test
    fun `parse SetGps command`() {
        val command = CommandType.parse("cmd-42", "setGps", mapOf("enabled" to false))

        assertThat(command).isInstanceOf(CommandType.SetGps::class.java)
        assertThat((command as CommandType.SetGps).enabled).isFalse()
    }

    @Test
    fun `parse setLocation as SetGps command`() {
        val command = CommandType.parse("cmd-43", "setLocation", mapOf("enabled" to true))

        assertThat(command).isInstanceOf(CommandType.SetGps::class.java)
        assertThat((command as CommandType.SetGps).enabled).isTrue()
    }

    @Test
    fun `parse SetUsb command`() {
        val command = CommandType.parse("cmd-44", "setUsb", mapOf("enabled" to false))

        assertThat(command).isInstanceOf(CommandType.SetUsb::class.java)
        assertThat((command as CommandType.SetUsb).enabled).isFalse()
    }

    @Test
    fun `parse SetMobileData command`() {
        val command = CommandType.parse("cmd-45", "setMobileData", mapOf("enabled" to true))

        assertThat(command).isInstanceOf(CommandType.SetMobileData::class.java)
        assertThat((command as CommandType.SetMobileData).enabled).isTrue()
    }

    // ============================================
    // Screen Commands
    // ============================================

    @Test
    fun `parse SetScreenshot command`() {
        val command = CommandType.parse("cmd-50", "setScreenshot", mapOf("disabled" to true))

        assertThat(command).isInstanceOf(CommandType.SetScreenshot::class.java)
        assertThat((command as CommandType.SetScreenshot).disabled).isTrue()
    }

    @Test
    fun `parse SetBrightness command`() {
        val command = CommandType.parse("cmd-51", "setBrightness", mapOf("level" to 200))

        assertThat(command).isInstanceOf(CommandType.SetBrightness::class.java)
        assertThat((command as CommandType.SetBrightness).level).isEqualTo(200)
    }

    @Test
    fun `parse SetScreenTimeout command`() {
        val command = CommandType.parse("cmd-52", "setScreenTimeout", mapOf("timeoutSeconds" to 300))

        assertThat(command).isInstanceOf(CommandType.SetScreenTimeout::class.java)
        assertThat((command as CommandType.SetScreenTimeout).timeoutSeconds).isEqualTo(300)
    }

    // ============================================
    // Network Commands
    // ============================================

    @Test
    fun `parse ConfigureWifi command`() {
        val command = CommandType.parse("cmd-60", "configureWifi", mapOf(
            "ssid" to "MyNetwork",
            "password" to "secret123",
            "securityType" to "WPA3",
            "hidden" to true
        ))

        assertThat(command).isInstanceOf(CommandType.ConfigureWifi::class.java)
        val wifi = command as CommandType.ConfigureWifi
        assertThat(wifi.ssid).isEqualTo("MyNetwork")
        assertThat(wifi.password).isEqualTo("secret123")
        assertThat(wifi.securityType).isEqualTo("WPA3")
        assertThat(wifi.hidden).isTrue()
    }

    @Test
    fun `parse setWifiNetwork as ConfigureWifi command`() {
        val command = CommandType.parse("cmd-61", "setWifiNetwork", mapOf(
            "ssid" to "AltNetwork"
        ))

        assertThat(command).isInstanceOf(CommandType.ConfigureWifi::class.java)
        assertThat((command as CommandType.ConfigureWifi).ssid).isEqualTo("AltNetwork")
    }

    @Test
    fun `parse RemoveWifi command`() {
        val command = CommandType.parse("cmd-62", "removeWifi", mapOf("ssid" to "OldNetwork"))

        assertThat(command).isInstanceOf(CommandType.RemoveWifi::class.java)
        assertThat((command as CommandType.RemoveWifi).ssid).isEqualTo("OldNetwork")
    }

    // ============================================
    // System Commands
    // ============================================

    @Test
    fun `parse Shell command`() {
        val command = CommandType.parse("cmd-70", "shell", mapOf(
            "command" to "pm list packages",
            "timeout" to 60000
        ))

        assertThat(command).isInstanceOf(CommandType.Shell::class.java)
        val shell = command as CommandType.Shell
        assertThat(shell.command).isEqualTo("pm list packages")
        assertThat(shell.timeout).isEqualTo(60000)
    }

    @Test
    fun `parse SetVolume command`() {
        val command = CommandType.parse("cmd-71", "setVolume", mapOf(
            "level" to 75,
            "streamType" to "ring"
        ))

        assertThat(command).isInstanceOf(CommandType.SetVolume::class.java)
        val volume = command as CommandType.SetVolume
        assertThat(volume.level).isEqualTo(75)
        assertThat(volume.streamType).isEqualTo("ring")
    }

    @Test
    fun `parse GetLocation command`() {
        val command = CommandType.parse("cmd-72", "getLocation", null)

        assertThat(command).isInstanceOf(CommandType.GetLocation::class.java)
    }

    @Test
    fun `parse SetTimeZone command`() {
        val command = CommandType.parse("cmd-73", "setTimeZone", mapOf(
            "timezone" to "America/New_York"
        ))

        assertThat(command).isInstanceOf(CommandType.SetTimeZone::class.java)
        assertThat((command as CommandType.SetTimeZone).timezone).isEqualTo("America/New_York")
    }

    @Test
    fun `parse SetAdb command`() {
        val command = CommandType.parse("cmd-74", "setAdb", mapOf("enabled" to true))

        assertThat(command).isInstanceOf(CommandType.SetAdb::class.java)
        assertThat((command as CommandType.SetAdb).enabled).isTrue()
    }

    @Test
    fun `parse enableAdb as SetAdb command`() {
        val command = CommandType.parse("cmd-75", "enableAdb", mapOf("enabled" to false))

        assertThat(command).isInstanceOf(CommandType.SetAdb::class.java)
        assertThat((command as CommandType.SetAdb).enabled).isFalse()
    }

    // ============================================
    // Restriction Commands
    // ============================================

    @Test
    fun `parse SetRestriction command`() {
        val command = CommandType.parse("cmd-80", "setRestriction", mapOf(
            "restriction" to "no_install_apps",
            "enabled" to true
        ))

        assertThat(command).isInstanceOf(CommandType.SetRestriction::class.java)
        val restriction = command as CommandType.SetRestriction
        assertThat(restriction.restriction).isEqualTo("no_install_apps")
        assertThat(restriction.enabled).isTrue()
    }

    @Test
    fun `parse SetRestrictions command with multiple restrictions`() {
        val command = CommandType.parse("cmd-81", "setRestrictions", mapOf(
            "restrictions" to mapOf(
                "no_install_apps" to true,
                "no_uninstall_apps" to true,
                "no_factory_reset" to false
            )
        ))

        assertThat(command).isInstanceOf(CommandType.SetRestrictions::class.java)
        val restrictions = (command as CommandType.SetRestrictions).restrictions
        assertThat(restrictions).containsEntry("no_install_apps", true)
        assertThat(restrictions).containsEntry("no_uninstall_apps", true)
        assertThat(restrictions).containsEntry("no_factory_reset", false)
    }

    // ============================================
    // File Commands
    // ============================================

    @Test
    fun `parse DeployFile command`() {
        val command = CommandType.parse("cmd-90", "deployFile", mapOf(
            "url" to "https://example.com/config.json",
            "path" to "internal://config/settings.json",
            "hash" to "sha256hash",
            "overwrite" to false
        ))

        assertThat(command).isInstanceOf(CommandType.DeployFile::class.java)
        val deploy = command as CommandType.DeployFile
        assertThat(deploy.url).isEqualTo("https://example.com/config.json")
        assertThat(deploy.path).isEqualTo("internal://config/settings.json")
        assertThat(deploy.hash).isEqualTo("sha256hash")
        assertThat(deploy.overwrite).isFalse()
    }

    @Test
    fun `parse DeleteFile command`() {
        val command = CommandType.parse("cmd-91", "deleteFile", mapOf(
            "path" to "internal://config/old.json"
        ))

        assertThat(command).isInstanceOf(CommandType.DeleteFile::class.java)
        assertThat((command as CommandType.DeleteFile).path).isEqualTo("internal://config/old.json")
    }

    // ============================================
    // Policy Commands
    // ============================================

    @Test
    fun `parse SetPolicy command`() {
        val policyData = mapOf(
            "kioskMode" to true,
            "mainApp" to "com.kiosk"
        )
        val command = CommandType.parse("cmd-100", "setPolicy", mapOf(
            "policy" to policyData
        ))

        assertThat(command).isInstanceOf(CommandType.SetPolicy::class.java)
        val policy = (command as CommandType.SetPolicy).policy
        assertThat(policy).containsEntry("kioskMode", true)
        assertThat(policy).containsEntry("mainApp", "com.kiosk")
    }

    @Test
    fun `parse SetPolicy uses payload as policy when no policy key`() {
        val command = CommandType.parse("cmd-101", "setPolicy", mapOf(
            "kioskMode" to true
        ))

        val policy = (command as CommandType.SetPolicy).policy
        assertThat(policy).containsEntry("kioskMode", true)
    }

    // ============================================
    // Notification Commands
    // ============================================

    @Test
    fun `parse SendNotification command`() {
        val command = CommandType.parse("cmd-110", "sendNotification", mapOf(
            "title" to "Alert",
            "body" to "Important message",
            "priority" to "LOW"
        ))

        assertThat(command).isInstanceOf(CommandType.SendNotification::class.java)
        val notification = command as CommandType.SendNotification
        assertThat(notification.title).isEqualTo("Alert")
        assertThat(notification.body).isEqualTo("Important message")
        assertThat(notification.priority).isEqualTo("LOW")
    }

    @Test
    fun `parse SendNotification with defaults`() {
        val command = CommandType.parse("cmd-111", "sendNotification", mapOf(
            "body" to "Message"
        ))

        val notification = command as CommandType.SendNotification
        assertThat(notification.title).isEqualTo("MDM")
        assertThat(notification.priority).isEqualTo("HIGH")
    }

    // ============================================
    // Unknown Commands
    // ============================================

    @Test
    fun `parse unknown command type returns Unknown`() {
        val command = CommandType.parse("cmd-999", "unknownCommand", mapOf(
            "someKey" to "someValue"
        ))

        assertThat(command).isInstanceOf(CommandType.Unknown::class.java)
        val unknown = command as CommandType.Unknown
        assertThat(unknown.type).isEqualTo("unknownCommand")
        assertThat(unknown.payload).containsEntry("someKey", "someValue")
    }

    @Test
    fun `parse custom command`() {
        val command = CommandType.parse("cmd-200", "custom", mapOf(
            "customType" to "myCustomAction",
            "data" to "customData"
        ))

        assertThat(command).isInstanceOf(CommandType.Custom::class.java)
        val custom = command as CommandType.Custom
        assertThat(custom.customType).isEqualTo("myCustomAction")
        assertThat(custom.payload).containsEntry("data", "customData")
    }

    // ============================================
    // CommandResult Tests
    // ============================================

    @Test
    fun `CommandResult success creates successful result`() {
        val result = CommandResult.success("Operation completed", mapOf("key" to "value"))

        assertThat(result.success).isTrue()
        assertThat(result.message).isEqualTo("Operation completed")
        assertThat(result.data).isEqualTo(mapOf("key" to "value"))
    }

    @Test
    fun `CommandResult failure creates failed result`() {
        val result = CommandResult.failure("Operation failed")

        assertThat(result.success).isFalse()
        assertThat(result.message).isEqualTo("Operation failed")
        assertThat(result.data).isNull()
    }
}
