package com.openmdm.library.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [PolicyMapper].
 *
 * Tests the conversion of raw policy maps from the server to typed [PolicySettings].
 * Covers type coercion, default values, edge cases, and backward compatibility.
 */
class PolicyMapperTest {

    // ============================================
    // Basic Conversion Tests
    // ============================================

    @Test
    fun `fromMap with null returns default PolicySettings`() {
        val result = PolicyMapper.fromMap(null)

        assertThat(result).isEqualTo(PolicySettings())
    }

    @Test
    fun `fromMap with empty map returns default PolicySettings`() {
        val result = PolicyMapper.fromMap(emptyMap())

        assertThat(result).isEqualTo(PolicySettings())
    }

    // ============================================
    // General Settings Tests
    // ============================================

    @Test
    fun `fromMap parses general settings correctly`() {
        val map = mapOf(
            "id" to "policy-123",
            "name" to "Test Policy",
            "version" to "1.0.0",
            "heartbeatInterval" to 120
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.policyId).isEqualTo("policy-123")
        assertThat(result.policyName).isEqualTo("Test Policy")
        assertThat(result.policyVersion).isEqualTo("1.0.0")
        assertThat(result.heartbeatInterval).isEqualTo(120)
    }

    @Test
    fun `fromMap supports alternative key names for general settings`() {
        val map = mapOf(
            "policyId" to "policy-456",
            "policyName" to "Alt Policy",
            "policyVersion" to "2.0.0"
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.policyId).isEqualTo("policy-456")
        assertThat(result.policyName).isEqualTo("Alt Policy")
        assertThat(result.policyVersion).isEqualTo("2.0.0")
    }

    // ============================================
    // Kiosk Mode Tests
    // ============================================

    @Test
    fun `fromMap parses kiosk mode settings`() {
        val map = mapOf(
            "kioskMode" to true,
            "mainApp" to "com.example.kiosk",
            "kioskPackages" to listOf("com.example.app1", "com.example.app2"),
            "kioskHome" to false,
            "kioskRecents" to true,
            "kioskNotifications" to true,
            "lockStatusBar" to true,
            "immersiveMode" to true
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.kioskMode).isTrue()
        assertThat(result.mainApp).isEqualTo("com.example.kiosk")
        assertThat(result.kioskPackages).containsExactly("com.example.app1", "com.example.app2")
        assertThat(result.kioskHome).isFalse()
        assertThat(result.kioskRecents).isTrue()
        assertThat(result.kioskNotifications).isTrue()
        assertThat(result.lockStatusBar).isTrue()
        assertThat(result.immersiveMode).isTrue()
    }

    @Test
    fun `fromMap supports alternative kiosk key names`() {
        val map = mapOf(
            "kiosk" to true,
            "kioskApp" to "com.example.alt",
            "disableStatusBar" to true
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.kioskMode).isTrue()
        assertThat(result.mainApp).isEqualTo("com.example.alt")
        assertThat(result.lockStatusBar).isTrue()
    }

    // ============================================
    // Hardware Control Tests
    // ============================================

    @Test
    fun `fromMap parses hardware control settings`() {
        val map = mapOf(
            "wifiEnabled" to false,
            "bluetoothEnabled" to true,
            "gpsEnabled" to false,
            "usbEnabled" to true,
            "mobileDataEnabled" to false,
            "nfcEnabled" to true,
            "hardwareEnforcementInterval" to 60
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.wifiEnabled).isFalse()
        assertThat(result.bluetoothEnabled).isTrue()
        assertThat(result.gpsEnabled).isFalse()
        assertThat(result.usbEnabled).isTrue()
        assertThat(result.mobileDataEnabled).isFalse()
        assertThat(result.nfcEnabled).isTrue()
        assertThat(result.hardwareEnforcementInterval).isEqualTo(60)
    }

    @Test
    fun `fromMap supports short hardware key names`() {
        val map = mapOf(
            "wifi" to true,
            "bluetooth" to false,
            "gps" to true,
            "location" to true,  // alternative for gps
            "usb" to false,
            "mobileData" to true
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.wifiEnabled).isTrue()
        assertThat(result.bluetoothEnabled).isFalse()
        assertThat(result.gpsEnabled).isTrue()
        assertThat(result.usbEnabled).isFalse()
        assertThat(result.mobileDataEnabled).isTrue()
    }

    // ============================================
    // Screen Settings Tests
    // ============================================

    @Test
    fun `fromMap parses screen settings`() {
        val map = mapOf(
            "screenshotDisabled" to true,
            "screenTimeoutSeconds" to 300,
            "brightnessLevel" to 128,
            "autoBrightness" to false,
            "keepScreenOn" to true
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.screenshotDisabled).isTrue()
        assertThat(result.screenTimeoutSeconds).isEqualTo(300)
        assertThat(result.brightnessLevel).isEqualTo(128)
        assertThat(result.autoBrightness).isFalse()
        assertThat(result.keepScreenOn).isTrue()
    }

    @Test
    fun `fromMap supports alternative screen key names`() {
        val map = mapOf(
            "disableScreenshot" to true,
            "screenTimeout" to 600,
            "brightness" to 200
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.screenshotDisabled).isTrue()
        assertThat(result.screenTimeoutSeconds).isEqualTo(600)
        assertThat(result.brightnessLevel).isEqualTo(200)
    }

    // ============================================
    // User Restrictions Tests
    // ============================================

    @Test
    fun `fromMap parses restriction list`() {
        val map = mapOf(
            "restrictions" to listOf("no_install_apps", "no_uninstall_apps", "no_factory_reset")
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.restrictions).containsExactly(
            "no_install_apps", "no_uninstall_apps", "no_factory_reset"
        )
    }

    @Test
    fun `fromMap parses restrictions from CSV string`() {
        val map = mapOf(
            "restrictionsCSV" to "no_install_apps, no_uninstall_apps, no_factory_reset"
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.restrictions).containsExactly(
            "no_install_apps", "no_uninstall_apps", "no_factory_reset"
        )
    }

    @Test
    fun `fromMap parses individual restriction flags`() {
        val map = mapOf(
            "disallowInstallApps" to true,
            "disallowUninstallApps" to true,
            "disallowFactoryReset" to true,
            "disallowDebugging" to true,
            "disallowCamera" to true
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.disallowInstallApps).isTrue()
        assertThat(result.disallowUninstallApps).isTrue()
        assertThat(result.disallowFactoryReset).isTrue()
        assertThat(result.disallowDebugging).isTrue()
        assertThat(result.disallowCamera).isTrue()
    }

    // ============================================
    // WiFi Networks Tests
    // ============================================

    @Test
    fun `fromMap parses WiFi networks`() {
        val map = mapOf(
            "wifiNetworks" to listOf(
                mapOf(
                    "ssid" to "CorporateWiFi",
                    "password" to "secret123",
                    "securityType" to "WPA2",
                    "hidden" to false,
                    "autoConnect" to true,
                    "priority" to 10
                ),
                mapOf(
                    "ssid" to "GuestWiFi",
                    "securityType" to "OPEN"
                )
            )
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.wifiNetworks).hasSize(2)

        val corporate = result.wifiNetworks[0]
        assertThat(corporate.ssid).isEqualTo("CorporateWiFi")
        assertThat(corporate.password).isEqualTo("secret123")
        assertThat(corporate.securityType).isEqualTo(WifiSecurityType.WPA2)
        assertThat(corporate.hidden).isFalse()
        assertThat(corporate.autoConnect).isTrue()
        assertThat(corporate.priority).isEqualTo(10)

        val guest = result.wifiNetworks[1]
        assertThat(guest.ssid).isEqualTo("GuestWiFi")
        assertThat(guest.securityType).isEqualTo(WifiSecurityType.OPEN)
    }

    @Test
    fun `fromMap parses WiFi network with enterprise config`() {
        val map = mapOf(
            "wifiNetworks" to listOf(
                mapOf(
                    "ssid" to "EnterpriseWiFi",
                    "securityType" to "WPA2_ENTERPRISE",
                    "eapConfig" to mapOf(
                        "method" to "PEAP",
                        "phase2Method" to "MSCHAPV2",
                        "identity" to "user@company.com",
                        "anonymousIdentity" to "anonymous@company.com"
                    )
                )
            )
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.wifiNetworks).hasSize(1)
        val network = result.wifiNetworks[0]
        assertThat(network.ssid).isEqualTo("EnterpriseWiFi")
        assertThat(network.securityType).isEqualTo(WifiSecurityType.WPA2_ENTERPRISE)
        assertThat(network.eapConfig).isNotNull()
        assertThat(network.eapConfig?.method).isEqualTo("PEAP")
        assertThat(network.eapConfig?.phase2Method).isEqualTo("MSCHAPV2")
        assertThat(network.eapConfig?.identity).isEqualTo("user@company.com")
    }

    // ============================================
    // App Management Tests
    // ============================================

    @Test
    fun `fromMap parses app lists`() {
        val map = mapOf(
            "allowedApps" to listOf("com.allowed.app1", "com.allowed.app2"),
            "blockedApps" to listOf("com.blocked.app1"),
            "autoGrantPermissions" to false
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.allowedApps).containsExactly("com.allowed.app1", "com.allowed.app2")
        assertThat(result.blockedApps).containsExactly("com.blocked.app1")
        assertThat(result.autoGrantPermissions).isFalse()
    }

    @Test
    fun `fromMap parses installed apps configuration`() {
        val map = mapOf(
            "applications" to listOf(
                mapOf(
                    "packageName" to "com.example.app",
                    "name" to "Example App",
                    "version" to "1.0.0",
                    "url" to "https://example.com/app.apk",
                    "hash" to "abc123",
                    "runAfterInstall" to true,
                    "runAtBoot" to true,
                    "showIcon" to false
                )
            )
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.installedApps).hasSize(1)
        val app = result.installedApps[0]
        assertThat(app.packageName).isEqualTo("com.example.app")
        assertThat(app.name).isEqualTo("Example App")
        assertThat(app.version).isEqualTo("1.0.0")
        assertThat(app.url).isEqualTo("https://example.com/app.apk")
        assertThat(app.hash).isEqualTo("abc123")
        assertThat(app.runAfterInstall).isTrue()
        assertThat(app.runAtBoot).isTrue()
        assertThat(app.showIcon).isFalse()
    }

    // ============================================
    // File Deployment Tests
    // ============================================

    @Test
    fun `fromMap parses file deployments`() {
        val map = mapOf(
            "fileDeployments" to listOf(
                mapOf(
                    "url" to "https://example.com/config.json",
                    "path" to "internal://config/settings.json",
                    "hash" to "sha256hash",
                    "overwrite" to false
                )
            )
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.fileDeployments).hasSize(1)
        val file = result.fileDeployments[0]
        assertThat(file.url).isEqualTo("https://example.com/config.json")
        assertThat(file.path).isEqualTo("internal://config/settings.json")
        assertThat(file.hash).isEqualTo("sha256hash")
        assertThat(file.overwrite).isFalse()
    }

    // ============================================
    // Type Coercion Tests
    // ============================================

    @Test
    fun `fromMap coerces boolean from various types`() {
        // Test string "true"
        assertThat(PolicyMapper.fromMap(mapOf("kioskMode" to "true")).kioskMode).isTrue()
        assertThat(PolicyMapper.fromMap(mapOf("kioskMode" to "false")).kioskMode).isFalse()

        // Test string "1" and "0"
        assertThat(PolicyMapper.fromMap(mapOf("kioskMode" to "1")).kioskMode).isTrue()
        assertThat(PolicyMapper.fromMap(mapOf("kioskMode" to "0")).kioskMode).isFalse()

        // Test string "yes" and "no"
        assertThat(PolicyMapper.fromMap(mapOf("kioskMode" to "yes")).kioskMode).isTrue()

        // Test number
        assertThat(PolicyMapper.fromMap(mapOf("kioskMode" to 1)).kioskMode).isTrue()
        assertThat(PolicyMapper.fromMap(mapOf("kioskMode" to 0)).kioskMode).isFalse()
    }

    @Test
    fun `fromMap coerces integer from string`() {
        val map = mapOf(
            "heartbeatInterval" to "120",
            "screenTimeoutSeconds" to "300"
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.heartbeatInterval).isEqualTo(120)
        assertThat(result.screenTimeoutSeconds).isEqualTo(300)
    }

    @Test
    fun `fromMap handles string list from comma-separated string`() {
        val map = mapOf(
            "allowedApps" to "com.app1, com.app2, com.app3"
        )

        val result = PolicyMapper.fromMap(map)

        assertThat(result.allowedApps).containsExactly("com.app1", "com.app2", "com.app3")
    }

    // ============================================
    // toMap Conversion Tests
    // ============================================

    @Test
    fun `toMap converts PolicySettings to map`() {
        val settings = PolicySettings(
            policyId = "test-policy",
            policyName = "Test",
            kioskMode = true,
            mainApp = "com.example.kiosk",
            wifiEnabled = false,
            screenshotDisabled = true
        )

        val map = PolicyMapper.toMap(settings)

        assertThat(map["policyId"]).isEqualTo("test-policy")
        assertThat(map["policyName"]).isEqualTo("Test")
        assertThat(map["kioskMode"]).isEqualTo(true)
        assertThat(map["mainApp"]).isEqualTo("com.example.kiosk")
        assertThat(map["wifiEnabled"]).isEqualTo(false)
        assertThat(map["screenshotDisabled"]).isEqualTo(true)
    }

    @Test
    fun `toMap omits null values`() {
        val settings = PolicySettings(
            policyId = "test",
            wifiEnabled = null,
            bluetoothEnabled = null
        )

        val map = PolicyMapper.toMap(settings)

        assertThat(map).containsKey("policyId")
        assertThat(map).doesNotContainKey("wifiEnabled")
        assertThat(map).doesNotContainKey("bluetoothEnabled")
    }

    // ============================================
    // Helper Config Extraction Tests
    // ============================================

    @Test
    fun `HardwarePolicy fromPolicySettings extracts hardware settings`() {
        val settings = PolicySettings(
            wifiEnabled = false,
            bluetoothEnabled = true,
            gpsEnabled = false,
            usbEnabled = true,
            mobileDataEnabled = false,
            hardwareEnforcementInterval = 45
        )

        val hardwarePolicy = HardwarePolicy.fromPolicySettings(settings)

        assertThat(hardwarePolicy.wifiEnabled).isFalse()
        assertThat(hardwarePolicy.bluetoothEnabled).isTrue()
        assertThat(hardwarePolicy.gpsEnabled).isFalse()
        assertThat(hardwarePolicy.usbEnabled).isTrue()
        assertThat(hardwarePolicy.mobileDataEnabled).isFalse()
        assertThat(hardwarePolicy.enforcementInterval).isEqualTo(45)
    }

    @Test
    fun `KioskConfig fromPolicySettings extracts kiosk settings`() {
        val settings = PolicySettings(
            kioskMode = true,
            mainApp = "com.example.kiosk",
            kioskPackages = listOf("com.app1", "com.app2"),
            kioskHome = false,
            kioskRecents = true,
            lockStatusBar = true,
            immersiveMode = true
        )

        val kioskConfig = KioskConfig.fromPolicySettings(settings)

        assertThat(kioskConfig.enabled).isTrue()
        assertThat(kioskConfig.mainApp).isEqualTo("com.example.kiosk")
        assertThat(kioskConfig.allowedPackages).containsExactly("com.app1", "com.app2")
        assertThat(kioskConfig.homeEnabled).isFalse()
        assertThat(kioskConfig.recentsEnabled).isTrue()
        assertThat(kioskConfig.statusBarLocked).isTrue()
        assertThat(kioskConfig.immersiveMode).isTrue()
    }

    // ============================================
    // Edge Cases
    // ============================================

    @Test
    fun `fromMap handles invalid WiFi network gracefully`() {
        val map = mapOf(
            "wifiNetworks" to listOf(
                mapOf("password" to "no-ssid"),  // Missing required SSID
                mapOf("ssid" to "ValidNetwork", "securityType" to "WPA2")
            )
        )

        val result = PolicyMapper.fromMap(map)

        // Should only contain the valid network
        assertThat(result.wifiNetworks).hasSize(1)
        assertThat(result.wifiNetworks[0].ssid).isEqualTo("ValidNetwork")
    }

    @Test
    fun `fromMap handles invalid app config gracefully`() {
        val map = mapOf(
            "applications" to listOf(
                mapOf("name" to "No Package"),  // Missing required packageName
                mapOf("packageName" to "com.valid.app")
            )
        )

        val result = PolicyMapper.fromMap(map)

        // Should only contain the valid app
        assertThat(result.installedApps).hasSize(1)
        assertThat(result.installedApps[0].packageName).isEqualTo("com.valid.app")
    }

    @Test
    fun `fromMap handles empty strings in CSV`() {
        val map = mapOf(
            "restrictionsCSV" to "no_install_apps, , no_factory_reset, "
        )

        val result = PolicyMapper.fromMap(map)

        // Should filter out empty strings
        assertThat(result.restrictions).containsExactly("no_install_apps", "no_factory_reset")
    }
}
