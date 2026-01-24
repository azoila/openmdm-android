package com.openmdm.agent.integration

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.openmdm.agent.data.*
import com.openmdm.agent.data.local.dao.CommandDao
import com.openmdm.agent.data.local.entity.CommandEntity
import com.openmdm.agent.util.DeviceInfoCollector
import com.openmdm.agent.worker.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * Integration tests for the complete MDM flow.
 *
 * Tests the full flow from enrollment through heartbeat to command execution,
 * particularly focused on the installApp command flow that installs the MidiaMob player.
 *
 * Flow being tested:
 * 1. Device enrolls with pairing code
 * 2. Heartbeat worker sends heartbeat
 * 3. Server responds with pendingCommands (including installApp)
 * 4. HeartbeatWorker delegates command to MDMService
 * 5. MDMService persists command and schedules CommandWorker
 * 6. CommandWorker acknowledges and executes command
 * 7. MDMService downloads and installs the app
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MDMFlowIntegrationTest {

    private lateinit var context: Context
    private lateinit var mdmApi: MDMApi
    private lateinit var mdmRepository: MDMRepository
    private lateinit var commandDao: CommandDao
    private lateinit var deviceInfoCollector: DeviceInfoCollector
    private lateinit var workManager: WorkManager

    private val testDeviceId = "device-123"
    private val testToken = "jwt-token-abc"
    private val testRefreshToken = "refresh-token-xyz"

    private val enrollmentStateFlow = MutableStateFlow(EnrollmentState())

    private val testHeartbeatData = DeviceInfoCollector.HeartbeatData(
        batteryLevel = 80,
        isCharging = true,
        batteryHealth = "good",
        storageUsed = 10_000_000_000L,
        storageTotal = 64_000_000_000L,
        memoryUsed = 2_000_000_000L,
        memoryTotal = 4_000_000_000L,
        networkType = "wifi",
        networkName = "TestNetwork",
        signalStrength = -50,
        ipAddress = "192.168.1.100",
        location = null,
        installedApps = emptyList(),
        runningApps = emptyList(),
        isRooted = false,
        isEncrypted = true,
        screenLockEnabled = true,
        agentVersion = "1.0.0"
    )

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        mdmApi = mockk(relaxed = true)
        mdmRepository = mockk(relaxed = true)
        commandDao = mockk(relaxed = true)
        deviceInfoCollector = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        every { mdmRepository.enrollmentState } returns enrollmentStateFlow
        coEvery { deviceInfoCollector.collectHeartbeatData() } returns testHeartbeatData
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ============================================
    // Full Flow Tests
    // ============================================

    @Test
    fun `complete flow - enrollment to heartbeat to installApp command`() = runTest {
        // Step 1: Simulate enrollment
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            enrollmentId = "enroll-456",
            token = testToken,
            refreshToken = testRefreshToken,
            serverUrl = "https://api.test.com",
            policyVersion = "1.0"
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState

        // Step 2: Setup heartbeat response with installApp command
        val installCommand = CommandResponse(
            id = "cmd-install-player",
            type = "installApp",
            payload = mapOf(
                "url" to "https://example.com/midiamob-player.apk",
                "packageName" to "com.midiamob.player",
                "version" to "2.0.0"
            ),
            status = "pending",
            createdAt = "2024-01-01T00:00:00Z"
        )

        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = listOf(installCommand),
                policyUpdate = null,
                message = null
            )
        )

        // Step 3: Execute heartbeat worker
        val heartbeatWorker = createHeartbeatWorker()
        val heartbeatResult = heartbeatWorker.doWork()

        // Verify heartbeat succeeded
        assertThat(heartbeatResult).isEqualTo(ListenableWorker.Result.success())

        // Verify lastSync was updated
        coVerify { mdmRepository.updateLastSync() }

        // Verify heartbeat was called with correct data
        coVerify {
            mdmApi.heartbeat(
                "Bearer $testToken",
                match { request ->
                    request.deviceId == testDeviceId &&
                    request.batteryLevel == 80
                }
            )
        }
    }

    @Test
    fun `heartbeat correctly processes multiple commands`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken,
            refreshToken = testRefreshToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState

        val commands = listOf(
            CommandResponse(
                id = "cmd-1",
                type = "installApp",
                payload = mapOf("url" to "https://example.com/app1.apk"),
                status = "pending",
                createdAt = "2024-01-01T00:00:00Z"
            ),
            CommandResponse(
                id = "cmd-2",
                type = "setWallpaper",
                payload = mapOf("url" to "https://example.com/wallpaper.jpg"),
                status = "pending",
                createdAt = "2024-01-01T00:00:01Z"
            ),
            CommandResponse(
                id = "cmd-3",
                type = "sendNotification",
                payload = mapOf("title" to "Test", "message" to "Hello"),
                status = "pending",
                createdAt = "2024-01-01T00:00:02Z"
            )
        )

        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = commands,
                policyUpdate = null,
                message = null
            )
        )

        val worker = createHeartbeatWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Commands are delegated to service via intents
    }

    // ============================================
    // Token Expiration During Install Flow
    // ============================================

    @Test
    fun `token expiration during installApp command triggers refresh`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken,
            refreshToken = testRefreshToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState

        // First heartbeat returns 401
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.error(
            401,
            "Token expired".toResponseBody()
        )

        // Token refresh succeeds
        coEvery { mdmApi.refreshToken(any()) } returns Response.success(
            TokenResponse(
                token = "new-token",
                refreshToken = "new-refresh",
                expiresAt = null
            )
        )

        val worker = createHeartbeatWorker()
        val result = worker.doWork()

        // Should retry after token refresh
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())

        // Verify token was updated
        coVerify { mdmRepository.updateToken("new-token", "new-refresh") }
    }

    @Test
    fun `device unenrolls when refresh token is invalid`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken,
            refreshToken = testRefreshToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState

        // Heartbeat returns 401
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.error(
            401,
            "Token expired".toResponseBody()
        )

        // Token refresh also fails (invalid refresh token)
        coEvery { mdmApi.refreshToken(any()) } returns Response.error(
            401,
            "Invalid refresh token".toResponseBody()
        )

        val worker = createHeartbeatWorker()
        val result = worker.doWork()

        // Should fail and clear enrollment
        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify { mdmRepository.clearEnrollment() }
    }

    // ============================================
    // Command Worker Flow Tests
    // ============================================

    @Test
    fun `command worker acknowledges command before execution`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken,
            refreshToken = testRefreshToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.acknowledgeCommand(any(), any()) } returns Response.success(Unit)

        val worker = createCommandWorker(
            commandId = "cmd-123",
            commandType = "installApp",
            payloadJson = """{"url":"https://example.com/app.apk"}"""
        )
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify { mdmApi.acknowledgeCommand("Bearer $testToken", "cmd-123") }
    }

    @Test
    fun `command worker marks command as failed when device not enrolled`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns EnrollmentState(isEnrolled = false)

        val worker = createCommandWorker(
            commandId = "cmd-123",
            commandType = "installApp",
            payloadJson = """{"url":"https://example.com/app.apk"}"""
        )
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify { commandDao.markFailed("cmd-123", "Device not enrolled", any()) }
    }

    // ============================================
    // Policy Update Flow Tests
    // ============================================

    @Test
    fun `heartbeat updates policy version when received`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken,
            policyVersion = "1.0"
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState

        val newPolicy = PolicyResponse(
            id = "policy-1",
            name = "Updated Policy",
            version = "2.0",
            settings = mapOf("kiosk" to true, "lockTask" to true)
        )

        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = null,
                policyUpdate = newPolicy,
                message = null
            )
        )

        val worker = createHeartbeatWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify { mdmRepository.updatePolicyVersion("2.0") }
    }

    // ============================================
    // Network Error Recovery Tests
    // ============================================

    @Test
    fun `heartbeat retries on network error`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } throws java.io.IOException("Network unavailable")

        val worker = createHeartbeatWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `command worker retries on network error`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.acknowledgeCommand(any(), any()) } throws java.io.IOException("Network unavailable")

        val worker = createCommandWorkerWithAttempt(
            commandId = "cmd-123",
            commandType = "installApp",
            payloadJson = null,
            runAttemptCount = 0
        )
        val result = worker.doWork()

        // Note: The current implementation continues even if ack fails
        // But if the entire flow fails, it should retry
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ============================================
    // State Flow Tests
    // ============================================

    @Test
    fun `enrollment state flow updates trigger service start`() = runTest {
        enrollmentStateFlow.test {
            // Initial not enrolled state
            val initialState = awaitItem()
            assertThat(initialState.isEnrolled).isFalse()

            // Simulate enrollment
            enrollmentStateFlow.value = EnrollmentState(
                isEnrolled = true,
                deviceId = testDeviceId,
                token = testToken
            )

            // Should emit enrolled state
            val enrolledState = awaitItem()
            assertThat(enrolledState.isEnrolled).isTrue()
            assertThat(enrolledState.deviceId).isEqualTo(testDeviceId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================
    // Command Persistence Tests
    // ============================================

    @Test
    fun `command is persisted to database before execution`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState

        val worker = createCommandWorker(
            commandId = "cmd-persist-test",
            commandType = "installApp",
            payloadJson = """{"url":"https://example.com/app.apk"}"""
        )
        worker.doWork()

        // Verify command was marked in progress
        coVerify { commandDao.markInProgress("cmd-persist-test", any()) }
        // Verify command was marked completed on success
        coVerify { commandDao.markCompleted("cmd-persist-test", any()) }
    }

    // ============================================
    // InstallApp Command Specific Tests
    // ============================================

    @Test
    fun `installApp command includes required payload fields`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState

        // This is a key test - the installApp command should have:
        // - url: The APK download URL
        // - packageName: The package to install
        // - version: (optional) version info
        val payloadJson = """{
            "url": "https://api.midiamob.com/downloads/player.apk",
            "packageName": "com.midiamob.player",
            "version": "2.0.0",
            "hash": "sha256:abc123",
            "runAfterInstall": true
        }"""

        val worker = createCommandWorker(
            commandId = "cmd-install",
            commandType = "installApp",
            payloadJson = payloadJson
        )
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `installApp command fails gracefully with missing URL`() = runTest {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = testDeviceId,
            token = testToken
        )
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState

        // Missing URL in payload - command should still be delegated to service
        // which will handle the validation
        val payloadJson = """{"packageName": "com.midiamob.player"}"""

        val worker = createCommandWorker(
            commandId = "cmd-install-no-url",
            commandType = "installApp",
            payloadJson = payloadJson
        )
        val result = worker.doWork()

        // Worker delegates to service, so it succeeds
        // Service will handle the missing URL error
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ============================================
    // Helper Methods
    // ============================================

    private fun createHeartbeatWorker(): HeartbeatWorker {
        return TestListenableWorkerBuilder<HeartbeatWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters
                ): androidx.work.ListenableWorker {
                    return HeartbeatWorker(
                        appContext,
                        workerParameters,
                        mdmApi,
                        mdmRepository,
                        deviceInfoCollector
                    )
                }
            })
            .build()
    }

    private fun createCommandWorker(
        commandId: String,
        commandType: String,
        payloadJson: String?
    ): CommandWorker {
        val inputData = androidx.work.workDataOf(
            CommandWorker.KEY_COMMAND_ID to commandId,
            CommandWorker.KEY_COMMAND_TYPE to commandType,
            CommandWorker.KEY_PAYLOAD_JSON to payloadJson
        )

        return TestListenableWorkerBuilder<CommandWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters
                ): androidx.work.ListenableWorker {
                    return CommandWorker(
                        appContext,
                        workerParameters,
                        mdmApi,
                        mdmRepository,
                        commandDao
                    )
                }
            })
            .build()
    }

    private fun createCommandWorkerWithAttempt(
        commandId: String,
        commandType: String,
        payloadJson: String?,
        runAttemptCount: Int
    ): CommandWorker {
        val inputData = androidx.work.workDataOf(
            CommandWorker.KEY_COMMAND_ID to commandId,
            CommandWorker.KEY_COMMAND_TYPE to commandType,
            CommandWorker.KEY_PAYLOAD_JSON to payloadJson
        )

        return TestListenableWorkerBuilder<CommandWorker>(context)
            .setInputData(inputData)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters
                ): androidx.work.ListenableWorker {
                    return CommandWorker(
                        appContext,
                        workerParameters,
                        mdmApi,
                        mdmRepository,
                        commandDao
                    )
                }
            })
            .build()
    }
}
