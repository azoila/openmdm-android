package com.openmdm.library.enrollment

import android.os.PersistableBundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [QREnrollmentParser].
 *
 * Tests QR code parsing in multiple formats (JSON, key-value, simple)
 * and QR code generation for device provisioning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class QREnrollmentParserTest {

    // ============================================
    // JSON Format Parsing Tests
    // ============================================

    @Test
    fun `parseQRCode parses standard Android provisioning JSON`() {
        val json = """
        {
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME": "com.openmdm.agent",
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.openmdm.agent/.receiver.MDMDeviceAdminReceiver",
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://example.com/agent.apk",
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": "abc123",
            "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true
        }
        """.trimIndent()

        val result = QREnrollmentParser.parseQRCode(json)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.adminPackageName).isEqualTo("com.openmdm.agent")
        assertThat(config.adminComponentName).isEqualTo("com.openmdm.agent/.receiver.MDMDeviceAdminReceiver")
        assertThat(config.downloadUrl).isEqualTo("https://example.com/agent.apk")
        assertThat(config.packageChecksum).isEqualTo("abc123")
        assertThat(config.skipEncryption).isTrue()
    }

    @Test
    fun `parseQRCode parses JSON with WiFi configuration`() {
        val json = """
        {
            "android.app.extra.PROVISIONING_WIFI_SSID": "CorporateWiFi",
            "android.app.extra.PROVISIONING_WIFI_HIDDEN": true,
            "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
            "android.app.extra.PROVISIONING_WIFI_PASSWORD": "secret123"
        }
        """.trimIndent()

        val result = QREnrollmentParser.parseQRCode(json)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.wifiConfig).isNotNull()
        assertThat(config.wifiConfig?.ssid).isEqualTo("CorporateWiFi")
        assertThat(config.wifiConfig?.hidden).isTrue()
        assertThat(config.wifiConfig?.securityType).isEqualTo("WPA")
        assertThat(config.wifiConfig?.password).isEqualTo("secret123")
    }

    @Test
    fun `parseQRCode parses JSON with admin extras bundle`() {
        val json = """
        {
            "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
                "openmdm.server_url": "https://mdm.example.com",
                "openmdm.device_secret": "secret123",
                "openmdm.policy_id": "policy-456",
                "openmdm.group_id": "group-789"
            }
        }
        """.trimIndent()

        val result = QREnrollmentParser.parseQRCode(json)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(config.deviceSecret).isEqualTo("secret123")
        assertThat(config.policyId).isEqualTo("policy-456")
        assertThat(config.groupId).isEqualTo("group-789")
    }

    @Test
    fun `parseQRCode parses JSON with OpenMDM fields at top level`() {
        val json = """
        {
            "serverUrl": "https://mdm.example.com",
            "deviceSecret": "secret123",
            "policyId": "policy-456"
        }
        """.trimIndent()

        val result = QREnrollmentParser.parseQRCode(json)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(config.deviceSecret).isEqualTo("secret123")
        assertThat(config.policyId).isEqualTo("policy-456")
    }

    @Test
    fun `parseQRCode parses JSON with provisioning options`() {
        val json = """
        {
            "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
            "android.app.extra.PROVISIONING_SKIP_USER_CONSENT": true,
            "android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS": true,
            "android.app.extra.PROVISIONING_USE_MOBILE_DATA": true,
            "android.app.extra.PROVISIONING_LOCALE": "en_US",
            "android.app.extra.PROVISIONING_TIME_ZONE": "America/New_York"
        }
        """.trimIndent()

        val result = QREnrollmentParser.parseQRCode(json)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.leaveAllSystemAppsEnabled).isTrue()
        assertThat(config.skipUserConsent).isTrue()
        assertThat(config.skipEducationScreens).isTrue()
        assertThat(config.useMobileData).isTrue()
        assertThat(config.locale).isEqualTo("en_US")
        assertThat(config.timeZone).isEqualTo("America/New_York")
    }

    // ============================================
    // Key-Value Format Parsing Tests
    // ============================================

    @Test
    fun `parseQRCode parses URL-encoded key-value format`() {
        val keyValue = "server_url=https%3A%2F%2Fmdm.example.com&device_secret=secret123&policy_id=policy-456"

        val result = QREnrollmentParser.parseQRCode(keyValue)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(config.deviceSecret).isEqualTo("secret123")
        assertThat(config.policyId).isEqualTo("policy-456")
    }

    @Test
    fun `parseQRCode parses key-value with admin package info`() {
        val keyValue = "admin_package=com.openmdm.agent&admin_component=com.openmdm.agent%2F.receiver.MDMDeviceAdminReceiver&download_url=https%3A%2F%2Fexample.com%2Fagent.apk"

        val result = QREnrollmentParser.parseQRCode(keyValue)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.adminPackageName).isEqualTo("com.openmdm.agent")
        assertThat(config.adminComponentName).isEqualTo("com.openmdm.agent/.receiver.MDMDeviceAdminReceiver")
        assertThat(config.downloadUrl).isEqualTo("https://example.com/agent.apk")
    }

    @Test
    fun `parseQRCode parses key-value with WiFi config`() {
        val keyValue = "server_url=https%3A%2F%2Fmdm.example.com&wifi_ssid=TestNetwork&wifi_password=pass123&wifi_security=WPA2"

        val result = QREnrollmentParser.parseQRCode(keyValue)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.wifiConfig).isNotNull()
        assertThat(config.wifiConfig?.ssid).isEqualTo("TestNetwork")
        assertThat(config.wifiConfig?.password).isEqualTo("pass123")
        assertThat(config.wifiConfig?.securityType).isEqualTo("WPA2")
    }

    // ============================================
    // Simple Format Parsing Tests
    // ============================================

    @Test
    fun `parseQRCode parses simple pipe-separated format`() {
        val simple = "https://mdm.example.com|secret123|policy-456|group-789"

        val result = QREnrollmentParser.parseQRCode(simple)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(config.deviceSecret).isEqualTo("secret123")
        assertThat(config.policyId).isEqualTo("policy-456")
        assertThat(config.groupId).isEqualTo("group-789")
    }

    @Test
    fun `parseQRCode parses simple format with just server URL`() {
        val simple = "https://mdm.example.com"

        val result = QREnrollmentParser.parseQRCode(simple)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(config.deviceSecret).isNull()
    }

    @Test
    fun `parseQRCode parses simple format with server and secret`() {
        val simple = "https://mdm.example.com|secret123"

        val result = QREnrollmentParser.parseQRCode(simple)

        assertThat(result.isSuccess).isTrue()
        val config = result.getOrThrow()
        assertThat(config.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(config.deviceSecret).isEqualTo("secret123")
        assertThat(config.policyId).isNull()
    }

    // ============================================
    // Format Detection Tests
    // ============================================

    @Test
    fun `parseQRCode detects JSON format by opening brace`() {
        val json = """{"serverUrl": "https://test.com"}"""

        val result = QREnrollmentParser.parseQRCode(json)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().serverUrl).isEqualTo("https://test.com")
    }

    @Test
    fun `parseQRCode detects key-value format by equals sign`() {
        val keyValue = "server_url=https://test.com"

        val result = QREnrollmentParser.parseQRCode(keyValue)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().serverUrl).isEqualTo("https://test.com")
    }

    @Test
    fun `parseQRCode detects simple format by pipe`() {
        val simple = "https://test.com|secret"

        val result = QREnrollmentParser.parseQRCode(simple)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().serverUrl).isEqualTo("https://test.com")
    }

    @Test
    fun `parseQRCode handles plain URL as server URL`() {
        val url = "https://mdm.example.com/api"

        val result = QREnrollmentParser.parseQRCode(url)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().serverUrl).isEqualTo("https://mdm.example.com/api")
    }

    @Test
    fun `parseQRCode fails for unrecognized format`() {
        val invalid = "not a valid format"

        val result = QREnrollmentParser.parseQRCode(invalid)

        assertThat(result.isFailure).isTrue()
    }

    // ============================================
    // QR Code Generation Tests
    // ============================================

    @Test
    fun `generateQRCode creates JSON format by default`() {
        val qr = QREnrollmentParser.generateQRCode(
            serverUrl = "https://mdm.example.com",
            deviceSecret = "secret123",
            policyId = "policy-456"
        )

        assertThat(qr).startsWith("{")
        assertThat(qr).contains("openmdm.server_url")
        // JSON escapes forward slashes
        assertThat(qr).contains("https:\\/\\/mdm.example.com")
        assertThat(qr).contains("secret123")
        assertThat(qr).contains("policy-456")
    }

    @Test
    fun `generateQRCode creates JSON with admin package info`() {
        val qr = QREnrollmentParser.generateQRCode(
            serverUrl = "https://mdm.example.com",
            adminPackageName = "com.openmdm.agent",
            adminComponentName = "com.openmdm.agent/.receiver.MDMDeviceAdminReceiver",
            downloadUrl = "https://example.com/agent.apk",
            packageChecksum = "abc123"
        )

        assertThat(qr).contains("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME")
        assertThat(qr).contains("com.openmdm.agent")
        assertThat(qr).contains("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION")
    }

    @Test
    fun `generateQRCode creates JSON with WiFi config`() {
        val qr = QREnrollmentParser.generateQRCode(
            serverUrl = "https://mdm.example.com",
            wifiSsid = "CorporateWiFi",
            wifiPassword = "secret123",
            wifiSecurityType = "WPA2",
            wifiHidden = true
        )

        assertThat(qr).contains("android.app.extra.PROVISIONING_WIFI_SSID")
        assertThat(qr).contains("CorporateWiFi")
        assertThat(qr).contains("android.app.extra.PROVISIONING_WIFI_PASSWORD")
        assertThat(qr).contains("android.app.extra.PROVISIONING_WIFI_HIDDEN")
    }

    @Test
    fun `generateQRCode creates KEY_VALUE format`() {
        val qr = QREnrollmentParser.generateQRCode(
            serverUrl = "https://mdm.example.com",
            deviceSecret = "secret123",
            format = QRFormat.KEY_VALUE
        )

        assertThat(qr).contains("server_url=")
        assertThat(qr).contains("device_secret=")
        assertThat(qr.startsWith("{")).isFalse()
    }

    @Test
    fun `generateQRCode creates SIMPLE format`() {
        val qr = QREnrollmentParser.generateQRCode(
            serverUrl = "https://mdm.example.com",
            deviceSecret = "secret123",
            policyId = "policy-456",
            groupId = "group-789",
            format = QRFormat.SIMPLE
        )

        assertThat(qr).isEqualTo("https://mdm.example.com|secret123|policy-456|group-789")
    }

    @Test
    fun `generateQRCode SIMPLE format omits trailing nulls`() {
        val qr = QREnrollmentParser.generateQRCode(
            serverUrl = "https://mdm.example.com",
            deviceSecret = "secret123",
            format = QRFormat.SIMPLE
        )

        assertThat(qr).isEqualTo("https://mdm.example.com|secret123")
    }

    // ============================================
    // Round-trip Tests
    // ============================================

    @Test
    fun `round-trip JSON format preserves data`() {
        val original = QREnrollmentParser.generateQRCode(
            serverUrl = "https://mdm.example.com",
            adminPackageName = "com.openmdm.agent",
            deviceSecret = "secret123",
            policyId = "policy-456",
            wifiSsid = "TestNetwork",
            wifiPassword = "wifiPass",
            format = QRFormat.JSON
        )

        val parsed = QREnrollmentParser.parseQRCode(original).getOrThrow()

        assertThat(parsed.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(parsed.adminPackageName).isEqualTo("com.openmdm.agent")
        assertThat(parsed.deviceSecret).isEqualTo("secret123")
        assertThat(parsed.policyId).isEqualTo("policy-456")
        assertThat(parsed.wifiConfig?.ssid).isEqualTo("TestNetwork")
        assertThat(parsed.wifiConfig?.password).isEqualTo("wifiPass")
    }

    @Test
    fun `round-trip SIMPLE format preserves data`() {
        val original = QREnrollmentParser.generateQRCode(
            serverUrl = "https://mdm.example.com",
            deviceSecret = "secret123",
            policyId = "policy-456",
            format = QRFormat.SIMPLE
        )

        val parsed = QREnrollmentParser.parseQRCode(original).getOrThrow()

        assertThat(parsed.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(parsed.deviceSecret).isEqualTo("secret123")
        assertThat(parsed.policyId).isEqualTo("policy-456")
    }

    // ============================================
    // PersistableBundle Conversion Tests
    // ============================================

    @Test
    fun `configToPersistableBundle creates bundle with OpenMDM fields`() {
        val config = EnrollmentConfig(
            serverUrl = "https://mdm.example.com",
            deviceSecret = "secret123",
            policyId = "policy-456",
            groupId = "group-789",
            configUrl = "https://config.example.com",
            enrollmentToken = "token123"
        )

        val bundle = QREnrollmentParser.configToPersistableBundle(config)

        assertThat(bundle.getString("openmdm.server_url")).isEqualTo("https://mdm.example.com")
        assertThat(bundle.getString("openmdm.device_secret")).isEqualTo("secret123")
        assertThat(bundle.getString("openmdm.policy_id")).isEqualTo("policy-456")
        assertThat(bundle.getString("openmdm.group_id")).isEqualTo("group-789")
        assertThat(bundle.getString("openmdm.config_url")).isEqualTo("https://config.example.com")
        assertThat(bundle.getString("openmdm.enrollment_token")).isEqualTo("token123")
    }

    @Test
    fun `bundleToConfig extracts config from PersistableBundle`() {
        val bundle = PersistableBundle().apply {
            putString("openmdm.server_url", "https://mdm.example.com")
            putString("openmdm.device_secret", "secret123")
            putString("openmdm.policy_id", "policy-456")
        }

        val config = QREnrollmentParser.bundleToConfig(bundle)

        assertThat(config).isNotNull()
        assertThat(config?.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(config?.deviceSecret).isEqualTo("secret123")
        assertThat(config?.policyId).isEqualTo("policy-456")
    }

    @Test
    fun `bundleToConfig returns null for bundle without server URL`() {
        val bundle = PersistableBundle().apply {
            putString("openmdm.device_secret", "secret123")
        }

        val config = QREnrollmentParser.bundleToConfig(bundle)

        assertThat(config).isNull()
    }

    @Test
    fun `bundleToConfig returns null for null bundle`() {
        val config = QREnrollmentParser.bundleToConfig(null)

        assertThat(config).isNull()
    }

    @Test
    fun `bundleToConfig supports alternative key names`() {
        val bundle = PersistableBundle().apply {
            putString("serverUrl", "https://mdm.example.com")
            putString("deviceSecret", "secret123")
        }

        val config = QREnrollmentParser.bundleToConfig(bundle)

        assertThat(config).isNotNull()
        assertThat(config?.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(config?.deviceSecret).isEqualTo("secret123")
    }

    // ============================================
    // Edge Cases
    // ============================================

    @Test
    fun `parseQRCode handles whitespace in content`() {
        val json = """
            {
                "serverUrl": "https://mdm.example.com"
            }
        """

        val result = QREnrollmentParser.parseQRCode(json)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().serverUrl).isEqualTo("https://mdm.example.com")
    }

    @Test
    fun `parseQRCode handles empty WiFi network list`() {
        val json = """{"wifiNetworks": []}"""

        val result = QREnrollmentParser.parseQRCode(json)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `parseQRCode handles malformed JSON gracefully`() {
        val malformed = """{"serverUrl": "incomplete"""

        val result = QREnrollmentParser.parseQRCode(malformed)

        assertThat(result.isFailure).isTrue()
    }
}
