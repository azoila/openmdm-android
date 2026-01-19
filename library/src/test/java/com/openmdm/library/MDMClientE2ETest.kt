package com.openmdm.library

import com.google.common.truth.Truth.assertThat
import com.openmdm.library.api.*
import com.openmdm.library.command.CommandType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-End tests for MDMClient against a mock OpenMDM server.
 *
 * These tests validate the complete MDM workflow:
 * - Device enrollment with HMAC signature verification
 * - Heartbeat cycle with command polling
 * - Command lifecycle (acknowledge, complete, fail)
 * - Token refresh flow
 * - Push token registration
 * - Policy updates
 */
class MDMClientE2ETest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: MDMClient

    private val testDeviceSecret = "test-shared-secret-12345"
    private val testDeviceId = "device-001"
    private val testToken = "jwt-token-abc123"
    private val testRefreshToken = "refresh-token-xyz789"

    @Before
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        client = MDMClient.Builder()
            .serverUrl(mockServer.url("/").toString())
            .deviceSecret(testDeviceSecret)
            .timeout(10)
            .debug(false)
            .build()
    }

    @After
    fun teardown() {
        mockServer.shutdown()
    }

    // ============================================
    // Enrollment Tests
    // ============================================

    @Test
    fun `enrollment - successful enrollment returns device credentials`() = runTest {
        // Given - server returns successful enrollment response
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "deviceId": "$testDeviceId",
                    "enrollmentId": "enroll-001",
                    "policyId": "policy-default",
                    "policy": {
                        "id": "policy-default",
                        "name": "Default Policy",
                        "version": "1.0",
                        "settings": {
                            "kioskEnabled": false,
                            "wifiEnabled": true
                        }
                    },
                    "serverUrl": "${mockServer.url("/")}",
                    "pushConfig": {
                        "provider": "fcm",
                        "fcmSenderId": "123456789",
                        "pollingInterval": 60
                    },
                    "token": "$testToken",
                    "refreshToken": "$testRefreshToken",
                    "tokenExpiresAt": "2025-12-31T23:59:59Z"
                }
            """.trimIndent()))

        // When
        val request = createTestEnrollmentRequest()
        val result = client.enroll(request)

        // Then
        assertThat(result.isSuccess).isTrue()
        val response = result.getOrThrow()
        assertThat(response.deviceId).isEqualTo(testDeviceId)
        assertThat(response.token).isEqualTo(testToken)
        assertThat(response.refreshToken).isEqualTo(testRefreshToken)
        assertThat(response.policy?.settings?.get("kioskEnabled")).isEqualTo(false)

        // Verify request was sent correctly
        val recordedRequest = mockServer.takeRequest()
        assertThat(recordedRequest.path).isEqualTo("/agent/enroll")
        assertThat(recordedRequest.method).isEqualTo("POST")

        // Verify client is now enrolled
        assertThat(client.isEnrolled()).isTrue()
        assertThat(client.getDeviceId()).isEqualTo(testDeviceId)
        assertThat(client.getToken()).isEqualTo(testToken)
    }

    @Test
    fun `enrollment - generates correct HMAC signature`() = runTest {
        // Given
        val model = "Pixel 6"
        val manufacturer = "Google"
        val osVersion = "14"
        val serialNumber = "SN12345"
        val method = "qr"
        val timestamp = "2025-01-01T12:00:00Z"

        // When
        val signature = client.generateEnrollmentSignature(
            model = model,
            manufacturer = manufacturer,
            osVersion = osVersion,
            serialNumber = serialNumber,
            imei = null,
            macAddress = null,
            androidId = "android123",
            method = method,
            timestamp = timestamp
        )

        // Then - signature should be a 64-char hex string (SHA-256)
        assertThat(signature).hasLength(64)
        assertThat(signature).matches("[a-f0-9]{64}")

        // Same inputs should produce same signature
        val signature2 = client.generateEnrollmentSignature(
            model = model,
            manufacturer = manufacturer,
            osVersion = osVersion,
            serialNumber = serialNumber,
            imei = null,
            macAddress = null,
            androidId = "android123",
            method = method,
            timestamp = timestamp
        )
        assertThat(signature).isEqualTo(signature2)
    }

    @Test
    fun `enrollment - failed enrollment returns error`() = runTest {
        // Given - server returns error
        mockServer.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"error": "Invalid signature"}"""))

        // When
        val result = client.enroll(createTestEnrollmentRequest())

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("401")
        assertThat(client.isEnrolled()).isFalse()
    }

    // ============================================
    // Heartbeat Tests
    // ============================================

    @Test
    fun `heartbeat - successful heartbeat returns pending commands`() = runTest {
        // Given - client is enrolled
        enrollTestDevice()

        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "success": true,
                    "pendingCommands": [
                        {
                            "id": "cmd-001",
                            "type": "lock",
                            "payload": {"message": "Device locked by admin"},
                            "status": "pending",
                            "createdAt": "2025-01-01T12:00:00Z"
                        },
                        {
                            "id": "cmd-002",
                            "type": "setWifi",
                            "payload": {"enabled": true},
                            "status": "pending",
                            "createdAt": "2025-01-01T12:01:00Z"
                        }
                    ],
                    "message": "OK"
                }
            """.trimIndent()))

        // When
        val request = createTestHeartbeatRequest()
        val result = client.heartbeat(request)

        // Then
        assertThat(result.isSuccess).isTrue()
        val response = result.getOrThrow()
        assertThat(response.success).isTrue()
        assertThat(response.pendingCommands).hasSize(2)

        val lockCmd = response.pendingCommands?.first()
        assertThat(lockCmd?.id).isEqualTo("cmd-001")
        assertThat(lockCmd?.type).isEqualTo("lock")

        // Verify authorization header was sent
        val recordedRequest = mockServer.takeRequest()
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer $testToken")
    }

    @Test
    fun `heartbeat - returns policy update when available`() = runTest {
        // Given
        enrollTestDevice()

        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "success": true,
                    "pendingCommands": [],
                    "policyUpdate": {
                        "id": "policy-v2",
                        "name": "Updated Policy",
                        "version": "2.0",
                        "settings": {
                            "kioskEnabled": true,
                            "kioskPackage": "com.example.kiosk",
                            "wifiEnabled": false
                        }
                    },
                    "message": "Policy updated"
                }
            """.trimIndent()))

        // When
        val result = client.heartbeat(createTestHeartbeatRequest())

        // Then
        assertThat(result.isSuccess).isTrue()
        val response = result.getOrThrow()
        assertThat(response.policyUpdate).isNotNull()
        assertThat(response.policyUpdate?.id).isEqualTo("policy-v2")
        assertThat(response.policyUpdate?.version).isEqualTo("2.0")
        assertThat(response.policyUpdate?.settings?.get("kioskEnabled")).isEqualTo(true)
    }

    @Test
    fun `heartbeat - fails when not enrolled`() = runTest {
        // Given - client is NOT enrolled
        assertThat(client.isEnrolled()).isFalse()

        // When
        val result = client.heartbeat(createTestHeartbeatRequest())

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Not enrolled")
    }

    @Test
    fun `heartbeat - triggers token refresh on 401`() = runTest {
        // Given
        var tokenRefreshed = false
        client = MDMClient.Builder()
            .serverUrl(mockServer.url("/").toString())
            .deviceSecret(testDeviceSecret)
            .onTokenRefresh { _, _ -> tokenRefreshed = true }
            .build()
        enrollTestDevice()

        // First heartbeat returns 401
        mockServer.enqueue(MockResponse().setResponseCode(401))

        // Token refresh succeeds
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "token": "new-token-456",
                    "refreshToken": "new-refresh-789",
                    "expiresAt": "2025-12-31T23:59:59Z"
                }
            """.trimIndent()))

        // Retry heartbeat succeeds
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"success": true, "pendingCommands": [], "message": "OK"}"""))

        // When
        val result = client.heartbeat(createTestHeartbeatRequest())

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(tokenRefreshed).isTrue()
    }

    // ============================================
    // Command Lifecycle Tests
    // ============================================

    @Test
    fun `command - acknowledge command succeeds`() = runTest {
        // Given
        enrollTestDevice()
        mockServer.enqueue(MockResponse().setResponseCode(200))

        // When
        val result = client.acknowledgeCommand("cmd-001")

        // Then
        assertThat(result.isSuccess).isTrue()
        val request = mockServer.takeRequest()
        assertThat(request.path).isEqualTo("/agent/commands/cmd-001/ack")
        assertThat(request.method).isEqualTo("POST")
    }

    @Test
    fun `command - complete command with success result`() = runTest {
        // Given
        enrollTestDevice()
        mockServer.enqueue(MockResponse().setResponseCode(200))

        // When
        val result = client.completeCommand(
            "cmd-001",
            CommandResultRequest(
                success = true,
                message = "Device locked successfully",
                data = mapOf("lockedAt" to "2025-01-01T12:00:00Z")
            )
        )

        // Then
        assertThat(result.isSuccess).isTrue()
        val request = mockServer.takeRequest()
        assertThat(request.path).isEqualTo("/agent/commands/cmd-001/complete")
        assertThat(request.body.readUtf8()).contains("\"success\":true")
    }

    @Test
    fun `command - fail command with error message`() = runTest {
        // Given
        enrollTestDevice()
        mockServer.enqueue(MockResponse().setResponseCode(200))

        // When
        val result = client.failCommand("cmd-001", "Permission denied: not device owner")

        // Then
        assertThat(result.isSuccess).isTrue()
        val request = mockServer.takeRequest()
        assertThat(request.path).isEqualTo("/agent/commands/cmd-001/fail")
        assertThat(request.body.readUtf8()).contains("Permission denied")
    }

    @Test
    fun `command - get pending commands returns list`() = runTest {
        // Given
        enrollTestDevice()
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "commands": [
                        {"id": "cmd-001", "type": "reboot", "payload": null, "status": "pending", "createdAt": "2025-01-01T12:00:00Z"},
                        {"id": "cmd-002", "type": "installApp", "payload": {"packageName": "com.example.app", "url": "https://example.com/app.apk"}, "status": "pending", "createdAt": "2025-01-01T12:01:00Z"}
                    ]
                }
            """.trimIndent()))

        // When
        val result = client.getPendingCommands()

        // Then
        assertThat(result.isSuccess).isTrue()
        val commands = result.getOrThrow()
        assertThat(commands).hasSize(2)
        assertThat(commands[0].type).isEqualTo("reboot")
        assertThat(commands[1].type).isEqualTo("installApp")
    }

    // ============================================
    // Push Token Tests
    // ============================================

    @Test
    fun `push token - register FCM token succeeds`() = runTest {
        // Given
        enrollTestDevice()
        mockServer.enqueue(MockResponse().setResponseCode(200))

        // When
        val result = client.registerPushToken("fcm", "fcm-token-abc123")

        // Then
        assertThat(result.isSuccess).isTrue()
        val request = mockServer.takeRequest()
        assertThat(request.path).isEqualTo("/agent/push-token")
        val requestBody = request.body.readUtf8()
        assertThat(requestBody).contains("fcm")
        assertThat(requestBody).contains("fcm-token-abc123")
    }

    // ============================================
    // Command Type Parsing Tests
    // ============================================

    @Test
    fun `command parsing - parses all device control commands`() {
        // Sync
        val sync = CommandType.parse("1", "sync", null)
        assertThat(sync).isInstanceOf(CommandType.Sync::class.java)

        // Reboot
        val reboot = CommandType.parse("2", "reboot", null)
        assertThat(reboot).isInstanceOf(CommandType.Reboot::class.java)

        // Lock with message
        val lock = CommandType.parse("3", "lock", mapOf("message" to "Locked by admin"))
        assertThat(lock).isInstanceOf(CommandType.Lock::class.java)
        assertThat((lock as CommandType.Lock).message).isEqualTo("Locked by admin")

        // Wipe
        val wipe = CommandType.parse("4", "wipe", mapOf("preserveData" to true))
        assertThat(wipe).isInstanceOf(CommandType.Wipe::class.java)
        assertThat((wipe as CommandType.Wipe).preserveData).isTrue()

        // Factory Reset
        val factoryReset = CommandType.parse("5", "factoryReset", null)
        assertThat(factoryReset).isInstanceOf(CommandType.FactoryReset::class.java)
    }

    @Test
    fun `command parsing - parses all app management commands`() {
        // InstallApp
        val install = CommandType.parse("1", "installApp", mapOf(
            "packageName" to "com.example.app",
            "url" to "https://example.com/app.apk",
            "version" to "1.0.0",
            "hash" to "abc123",
            "runAfterInstall" to true
        ))
        assertThat(install).isInstanceOf(CommandType.InstallApp::class.java)
        with(install as CommandType.InstallApp) {
            assertThat(packageName).isEqualTo("com.example.app")
            assertThat(url).isEqualTo("https://example.com/app.apk")
            assertThat(runAfterInstall).isTrue()
        }

        // UninstallApp
        val uninstall = CommandType.parse("2", "uninstallApp", mapOf("packageName" to "com.example.app"))
        assertThat(uninstall).isInstanceOf(CommandType.UninstallApp::class.java)
        assertThat((uninstall as CommandType.UninstallApp).packageName).isEqualTo("com.example.app")

        // RunApp
        val run = CommandType.parse("3", "runApp", mapOf(
            "packageName" to "com.example.app",
            "activity" to ".MainActivity"
        ))
        assertThat(run).isInstanceOf(CommandType.RunApp::class.java)
        assertThat((run as CommandType.RunApp).activity).isEqualTo(".MainActivity")
    }

    @Test
    fun `command parsing - parses all hardware commands`() {
        val commands = listOf(
            Triple("setWifi", mapOf("enabled" to true), CommandType.SetWifi::class.java),
            Triple("setBluetooth", mapOf("enabled" to false), CommandType.SetBluetooth::class.java),
            Triple("setGps", mapOf("enabled" to true), CommandType.SetGps::class.java),
            Triple("setUsb", mapOf("enabled" to false), CommandType.SetUsb::class.java),
            Triple("setMobileData", mapOf("enabled" to true), CommandType.SetMobileData::class.java),
            Triple("setNfc", mapOf("enabled" to true), CommandType.SetNfc::class.java)
        )

        commands.forEach { (type, payload, expectedClass) ->
            val cmd = CommandType.parse("test-id", type, payload)
            assertThat(cmd).isInstanceOf(expectedClass)
        }
    }

    @Test
    fun `command parsing - parses kiosk commands`() {
        // Enter Kiosk
        val enterKiosk = CommandType.parse("1", "enterKiosk", mapOf(
            "packageName" to "com.example.kiosk",
            "allowedPackages" to listOf("com.android.settings"),
            "lockStatusBar" to true,
            "hideNavigationBar" to true
        ))
        assertThat(enterKiosk).isInstanceOf(CommandType.EnterKiosk::class.java)
        with(enterKiosk as CommandType.EnterKiosk) {
            assertThat(packageName).isEqualTo("com.example.kiosk")
            assertThat(allowedPackages).contains("com.android.settings")
            assertThat(lockStatusBar).isTrue()
            assertThat(hideNavigationBar).isTrue()
        }

        // Exit Kiosk
        val exitKiosk = CommandType.parse("2", "exitKiosk", null)
        assertThat(exitKiosk).isInstanceOf(CommandType.ExitKiosk::class.java)
    }

    @Test
    fun `command parsing - parses screen commands`() {
        val screenshot = CommandType.parse("1", "setScreenshot", mapOf("disabled" to true))
        assertThat(screenshot).isInstanceOf(CommandType.SetScreenshot::class.java)
        assertThat((screenshot as CommandType.SetScreenshot).disabled).isTrue()

        val brightness = CommandType.parse("2", "setBrightness", mapOf("level" to 200))
        assertThat(brightness).isInstanceOf(CommandType.SetBrightness::class.java)
        assertThat((brightness as CommandType.SetBrightness).level).isEqualTo(200)

        val timeout = CommandType.parse("3", "setScreenTimeout", mapOf("timeoutSeconds" to 120))
        assertThat(timeout).isInstanceOf(CommandType.SetScreenTimeout::class.java)
        assertThat((timeout as CommandType.SetScreenTimeout).timeoutSeconds).isEqualTo(120)
    }

    @Test
    fun `command parsing - parses network commands`() {
        val configureWifi = CommandType.parse("1", "configureWifi", mapOf(
            "ssid" to "TestNetwork",
            "password" to "secret123",
            "securityType" to "WPA3",
            "hidden" to true
        ))
        assertThat(configureWifi).isInstanceOf(CommandType.ConfigureWifi::class.java)
        with(configureWifi as CommandType.ConfigureWifi) {
            assertThat(ssid).isEqualTo("TestNetwork")
            assertThat(password).isEqualTo("secret123")
            assertThat(securityType).isEqualTo("WPA3")
            assertThat(hidden).isTrue()
        }

        // Alternative name
        val setWifiNetwork = CommandType.parse("2", "setWifiNetwork", mapOf("ssid" to "Other"))
        assertThat(setWifiNetwork).isInstanceOf(CommandType.ConfigureWifi::class.java)
    }

    @Test
    fun `command parsing - parses system commands`() {
        // Shell
        val shell = CommandType.parse("1", "shell", mapOf(
            "command" to "pm list packages",
            "timeout" to 5000
        ))
        assertThat(shell).isInstanceOf(CommandType.Shell::class.java)
        with(shell as CommandType.Shell) {
            assertThat(command).isEqualTo("pm list packages")
            assertThat(timeout).isEqualTo(5000)
        }

        // Volume
        val volume = CommandType.parse("2", "setVolume", mapOf(
            "level" to 75,
            "streamType" to "ring"
        ))
        assertThat(volume).isInstanceOf(CommandType.SetVolume::class.java)
        with(volume as CommandType.SetVolume) {
            assertThat(level).isEqualTo(75)
            assertThat(streamType).isEqualTo("ring")
        }

        // Timezone alternatives
        val tz1 = CommandType.parse("3", "setTimeZone", mapOf("timezone" to "America/New_York"))
        assertThat(tz1).isInstanceOf(CommandType.SetTimeZone::class.java)
        val tz2 = CommandType.parse("4", "setTimezone", mapOf("timezone" to "UTC"))
        assertThat(tz2).isInstanceOf(CommandType.SetTimeZone::class.java)

        // ADB alternatives
        val adb1 = CommandType.parse("5", "setAdb", mapOf("enabled" to true))
        assertThat(adb1).isInstanceOf(CommandType.SetAdb::class.java)
        val adb2 = CommandType.parse("6", "enableAdb", mapOf("enabled" to false))
        assertThat(adb2).isInstanceOf(CommandType.SetAdb::class.java)
    }

    @Test
    fun `command parsing - parses restriction commands`() {
        // Single restriction
        val setRestriction = CommandType.parse("1", "setRestriction", mapOf(
            "restriction" to "no_install_apps",
            "enabled" to true
        ))
        assertThat(setRestriction).isInstanceOf(CommandType.SetRestriction::class.java)
        with(setRestriction as CommandType.SetRestriction) {
            assertThat(restriction).isEqualTo("no_install_apps")
            assertThat(enabled).isTrue()
        }

        // Multiple restrictions
        val setRestrictions = CommandType.parse("2", "setRestrictions", mapOf(
            "restrictions" to mapOf(
                "no_install_apps" to true,
                "no_uninstall_apps" to true,
                "no_factory_reset" to false
            )
        ))
        assertThat(setRestrictions).isInstanceOf(CommandType.SetRestrictions::class.java)
    }

    @Test
    fun `command parsing - parses file commands`() {
        // Deploy file
        val deployFile = CommandType.parse("1", "deployFile", mapOf(
            "url" to "https://example.com/config.json",
            "path" to "internal://config/settings.json",
            "hash" to "sha256:abc123",
            "overwrite" to true
        ))
        assertThat(deployFile).isInstanceOf(CommandType.DeployFile::class.java)
        with(deployFile as CommandType.DeployFile) {
            assertThat(url).isEqualTo("https://example.com/config.json")
            assertThat(path).isEqualTo("internal://config/settings.json")
            assertThat(hash).isEqualTo("sha256:abc123")
        }

        // Delete file
        val deleteFile = CommandType.parse("2", "deleteFile", mapOf("path" to "/sdcard/temp/file.txt"))
        assertThat(deleteFile).isInstanceOf(CommandType.DeleteFile::class.java)
        assertThat((deleteFile as CommandType.DeleteFile).path).isEqualTo("/sdcard/temp/file.txt")
    }

    @Test
    fun `command parsing - parses notification command`() {
        val notification = CommandType.parse("1", "sendNotification", mapOf(
            "title" to "MDM Alert",
            "body" to "Your device policy has been updated",
            "priority" to "HIGH"
        ))
        assertThat(notification).isInstanceOf(CommandType.SendNotification::class.java)
        with(notification as CommandType.SendNotification) {
            assertThat(title).isEqualTo("MDM Alert")
            assertThat(body).isEqualTo("Your device policy has been updated")
            assertThat(priority).isEqualTo("HIGH")
        }
    }

    @Test
    fun `command parsing - handles unknown commands`() {
        val unknown = CommandType.parse("1", "futureCommand", mapOf("data" to "value"))
        assertThat(unknown).isInstanceOf(CommandType.Unknown::class.java)
        with(unknown as CommandType.Unknown) {
            assertThat(type).isEqualTo("futureCommand")
            assertThat(payload).containsEntry("data", "value")
        }
    }

    @Test
    fun `command parsing - handles custom commands`() {
        val custom = CommandType.parse("1", "custom", mapOf(
            "customType" to "myCustomAction",
            "param1" to "value1",
            "param2" to 42
        ))
        assertThat(custom).isInstanceOf(CommandType.Custom::class.java)
        with(custom as CommandType.Custom) {
            assertThat(customType).isEqualTo("myCustomAction")
            assertThat(payload).containsEntry("param1", "value1")
        }
    }

    // ============================================
    // Full E2E Workflow Tests
    // ============================================

    @Test
    fun `e2e - complete enrollment and heartbeat workflow`() = runTest {
        // Step 1: Enroll device
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "deviceId": "$testDeviceId",
                    "enrollmentId": "enroll-001",
                    "policyId": "policy-default",
                    "policy": {"id": "policy-default", "name": "Default", "version": "1.0", "settings": {}},
                    "serverUrl": "${mockServer.url("/")}",
                    "pushConfig": {"provider": "polling", "pollingInterval": 60},
                    "token": "$testToken",
                    "refreshToken": "$testRefreshToken"
                }
            """.trimIndent()))

        val enrollResult = client.enroll(createTestEnrollmentRequest())
        assertThat(enrollResult.isSuccess).isTrue()

        // Step 2: Send heartbeat
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "success": true,
                    "pendingCommands": [
                        {"id": "cmd-001", "type": "lock", "payload": {"message": "Locked"}, "status": "pending", "createdAt": "2025-01-01T12:00:00Z"}
                    ]
                }
            """.trimIndent()))

        val heartbeatResult = client.heartbeat(createTestHeartbeatRequest())
        assertThat(heartbeatResult.isSuccess).isTrue()
        assertThat(heartbeatResult.getOrThrow().pendingCommands).hasSize(1)

        // Step 3: Acknowledge command
        mockServer.enqueue(MockResponse().setResponseCode(200))
        val ackResult = client.acknowledgeCommand("cmd-001")
        assertThat(ackResult.isSuccess).isTrue()

        // Step 4: Complete command
        mockServer.enqueue(MockResponse().setResponseCode(200))
        val completeResult = client.completeCommand(
            "cmd-001",
            CommandResultRequest(true, "Device locked", null)
        )
        assertThat(completeResult.isSuccess).isTrue()
    }

    @Test
    fun `e2e - command execution with policy update`() = runTest {
        // Enroll
        enrollTestDevice()

        // Heartbeat with policy update and commands
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "success": true,
                    "pendingCommands": [
                        {"id": "cmd-001", "type": "enterKiosk", "payload": {"packageName": "com.kiosk.app", "lockStatusBar": true}, "status": "pending", "createdAt": "2025-01-01T12:00:00Z"},
                        {"id": "cmd-002", "type": "setRestriction", "payload": {"restriction": "no_install_apps", "enabled": true}, "status": "pending", "createdAt": "2025-01-01T12:00:01Z"}
                    ],
                    "policyUpdate": {
                        "id": "policy-kiosk",
                        "name": "Kiosk Policy",
                        "version": "2.0",
                        "settings": {
                            "kioskEnabled": true,
                            "kioskPackage": "com.kiosk.app"
                        }
                    }
                }
            """.trimIndent()))

        val heartbeatResult = client.heartbeat(createTestHeartbeatRequest())
        assertThat(heartbeatResult.isSuccess).isTrue()

        val response = heartbeatResult.getOrThrow()

        // Verify policy update
        assertThat(response.policyUpdate).isNotNull()
        assertThat(response.policyUpdate?.settings?.get("kioskEnabled")).isEqualTo(true)

        // Verify commands
        assertThat(response.pendingCommands).hasSize(2)

        // Parse and verify command types
        val cmd1 = response.pendingCommands!![0]
        val parsedCmd1 = CommandType.parse(cmd1.id, cmd1.type, cmd1.payload)
        assertThat(parsedCmd1).isInstanceOf(CommandType.EnterKiosk::class.java)
        assertThat((parsedCmd1 as CommandType.EnterKiosk).packageName).isEqualTo("com.kiosk.app")

        val cmd2 = response.pendingCommands!![1]
        val parsedCmd2 = CommandType.parse(cmd2.id, cmd2.type, cmd2.payload)
        assertThat(parsedCmd2).isInstanceOf(CommandType.SetRestriction::class.java)
    }

    // ============================================
    // Helper Methods
    // ============================================

    private fun enrollTestDevice() {
        client.setCredentials(testDeviceId, testToken, testRefreshToken)
    }

    private fun createTestEnrollmentRequest(): EnrollmentRequest {
        return EnrollmentRequest(
            model = "Pixel 6",
            manufacturer = "Google",
            osVersion = "14",
            sdkVersion = 34,
            serialNumber = "SN12345",
            imei = null,
            macAddress = "AA:BB:CC:DD:EE:FF",
            androidId = "android123",
            agentVersion = "1.0.0",
            agentPackage = "com.openmdm.agent",
            method = "qr",
            timestamp = "2025-01-01T12:00:00Z",
            signature = client.generateEnrollmentSignature(
                model = "Pixel 6",
                manufacturer = "Google",
                osVersion = "14",
                serialNumber = "SN12345",
                imei = null,
                macAddress = "AA:BB:CC:DD:EE:FF",
                androidId = "android123",
                method = "qr",
                timestamp = "2025-01-01T12:00:00Z"
            )
        )
    }

    private fun createTestHeartbeatRequest(): HeartbeatRequest {
        return HeartbeatRequest(
            deviceId = testDeviceId,
            timestamp = "2025-01-01T12:05:00Z",
            batteryLevel = 85,
            isCharging = true,
            batteryHealth = "GOOD",
            storageUsed = 32_000_000_000L,
            storageTotal = 128_000_000_000L,
            memoryUsed = 2_000_000_000L,
            memoryTotal = 8_000_000_000L,
            networkType = "WIFI",
            networkName = "TestNetwork",
            signalStrength = -50,
            ipAddress = "192.168.1.100",
            location = LocationData(37.7749, -122.4194, 10f),
            installedApps = listOf(
                InstalledAppData("com.openmdm.agent", "1.0.0", 1),
                InstalledAppData("com.android.chrome", "120.0.0", 120)
            ),
            runningApps = listOf("com.openmdm.agent"),
            isRooted = false,
            isEncrypted = true,
            screenLockEnabled = true,
            agentVersion = "1.0.0",
            policyVersion = "1.0"
        )
    }
}
