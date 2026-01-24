package com.openmdm.agent.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.openmdm.agent.data.*
import com.openmdm.agent.util.DeviceInfoCollector
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.IOException

/**
 * Unit tests for [HeartbeatWorker].
 *
 * Tests heartbeat sending, token refresh, command processing, and error handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeartbeatWorkerTest {

    private lateinit var context: Context
    private lateinit var mdmApi: MDMApi
    private lateinit var mdmRepository: MDMRepository
    private lateinit var deviceInfoCollector: DeviceInfoCollector

    private val testDeviceId = "test-device-123"
    private val testToken = "test-jwt-token"
    private val testRefreshToken = "test-refresh-token"
    private val testPolicyVersion = "1.0"

    private val enrolledState = EnrollmentState(
        isEnrolled = true,
        deviceId = testDeviceId,
        enrollmentId = "enroll-456",
        token = testToken,
        refreshToken = testRefreshToken,
        serverUrl = "https://api.test.com",
        policyVersion = testPolicyVersion,
        lastSync = System.currentTimeMillis()
    )

    private val notEnrolledState = EnrollmentState(
        isEnrolled = false
    )

    private val testHeartbeatData = DeviceInfoCollector.HeartbeatData(
        batteryLevel = 75,
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
        installedApps = listOf(
            DeviceInfoCollector.InstalledAppInfo("com.test.app", "1.0", 1)
        ),
        runningApps = listOf("com.test.app"),
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
        deviceInfoCollector = mockk(relaxed = true)

        coEvery { deviceInfoCollector.collectHeartbeatData() } returns testHeartbeatData
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ============================================
    // Success Cases
    // ============================================

    @Test
    fun `heartbeat succeeds when enrolled and server returns 200`() = runTest {
        // Setup
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = null,
                policyUpdate = null,
                message = null
            )
        )

        // Execute
        val worker = createWorker()
        val result = worker.doWork()

        // Verify
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify { mdmRepository.updateLastSync() }
    }

    @Test
    fun `heartbeat processes pending commands from response`() = runTest {
        val pendingCommand = CommandResponse(
            id = "cmd-123",
            type = "installApp",
            payload = mapOf("url" to "https://example.com/app.apk"),
            status = "pending",
            createdAt = "2024-01-01T00:00:00Z"
        )

        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = listOf(pendingCommand),
                policyUpdate = null,
                message = null
            )
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Note: Command processing is delegated to MDMService via intent
    }

    @Test
    fun `heartbeat updates policy version when policyUpdate is received`() = runTest {
        val policyUpdate = PolicyResponse(
            id = "policy-1",
            name = "Test Policy",
            version = "2.0",
            settings = mapOf("kiosk" to true)
        )

        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = null,
                policyUpdate = policyUpdate,
                message = null
            )
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify { mdmRepository.updatePolicyVersion("2.0") }
    }

    // ============================================
    // Not Enrolled Cases
    // ============================================

    @Test
    fun `heartbeat returns success when device not enrolled`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns notEnrolledState

        val worker = createWorker()
        val result = worker.doWork()

        // Should succeed silently - no heartbeat needed when not enrolled
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { mdmApi.heartbeat(any(), any()) }
    }

    @Test
    fun `heartbeat returns success when token is null`() = runTest {
        val stateWithoutToken = enrolledState.copy(token = null)
        coEvery { mdmRepository.getEnrollmentState() } returns stateWithoutToken

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { mdmApi.heartbeat(any(), any()) }
    }

    // ============================================
    // Token Expiration and Refresh Tests
    // ============================================

    @Test
    fun `heartbeat attempts token refresh on 401 response`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.error(
            401,
            "Unauthorized".toResponseBody()
        )
        coEvery { mdmApi.refreshToken(any()) } returns Response.success(
            TokenResponse(
                token = "new-token",
                refreshToken = "new-refresh",
                expiresAt = null
            )
        )

        val worker = createWorker()
        val result = worker.doWork()

        // Should retry after successful token refresh
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        coVerify { mdmApi.refreshToken(RefreshTokenRequest(testRefreshToken)) }
        coVerify { mdmRepository.updateToken("new-token", "new-refresh") }
    }

    @Test
    fun `heartbeat clears enrollment when token refresh fails`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.error(
            401,
            "Unauthorized".toResponseBody()
        )
        coEvery { mdmApi.refreshToken(any()) } returns Response.error(
            401,
            "Invalid refresh token".toResponseBody()
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify { mdmRepository.clearEnrollment() }
    }

    @Test
    fun `heartbeat fails when no refresh token available for 401`() = runTest {
        val stateWithoutRefreshToken = enrolledState.copy(refreshToken = null)
        coEvery { mdmRepository.getEnrollmentState() } returns stateWithoutRefreshToken
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.error(
            401,
            "Unauthorized".toResponseBody()
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify(exactly = 0) { mdmApi.refreshToken(any()) }
    }

    // ============================================
    // Network Error Cases
    // ============================================

    @Test
    fun `heartbeat retries on IOException`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } throws IOException("Network error")

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `heartbeat retries on 5xx server error`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.error(
            503,
            "Service Unavailable".toResponseBody()
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `heartbeat fails on 4xx client error (except 401)`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.error(
            400,
            "Bad Request".toResponseBody()
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `heartbeat fails on 404 not found`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.error(
            404,
            "Not Found".toResponseBody()
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    // ============================================
    // Request Validation Tests
    // ============================================

    @Test
    fun `heartbeat sends correct request data`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(success = true, pendingCommands = null, policyUpdate = null, message = null)
        )

        val worker = createWorker()
        worker.doWork()

        coVerify {
            mdmApi.heartbeat(
                "Bearer $testToken",
                match { request ->
                    request.deviceId == testDeviceId &&
                    request.batteryLevel == 75 &&
                    request.isCharging == true &&
                    request.networkType == "wifi" &&
                    request.policyVersion == testPolicyVersion &&
                    request.agentVersion == "1.0.0"
                }
            )
        }
    }

    // ============================================
    // Multiple Commands Tests
    // ============================================

    @Test
    fun `heartbeat processes multiple pending commands`() = runTest {
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
                type = "lock",
                payload = null,
                status = "pending",
                createdAt = "2024-01-01T00:00:01Z"
            ),
            CommandResponse(
                id = "cmd-3",
                type = "setWallpaper",
                payload = mapOf("url" to "https://example.com/wallpaper.jpg"),
                status = "pending",
                createdAt = "2024-01-01T00:00:02Z"
            )
        )

        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = commands,
                policyUpdate = null,
                message = null
            )
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Commands are processed via service intents
    }

    // ============================================
    // Edge Cases
    // ============================================

    @Test
    fun `heartbeat handles empty pending commands list`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = emptyList(),
                policyUpdate = null,
                message = null
            )
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `heartbeat handles null policy version in update`() = runTest {
        val policyUpdate = PolicyResponse(
            id = "policy-1",
            name = "Test Policy",
            version = null,
            settings = mapOf("kiosk" to true)
        )

        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } returns Response.success(
            HeartbeatResponse(
                success = true,
                pendingCommands = null,
                policyUpdate = policyUpdate,
                message = null
            )
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Should not call updatePolicyVersion when version is null
        coVerify(exactly = 0) { mdmRepository.updatePolicyVersion(any()) }
    }

    @Test
    fun `heartbeat handles RuntimeException gracefully`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.heartbeat(any(), any()) } throws RuntimeException("Unexpected error")

        val worker = createWorker()
        val result = worker.doWork()

        // Should retry for unexpected exceptions (up to max attempts)
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    // ============================================
    // Helper Methods
    // ============================================

    private fun createWorker(): HeartbeatWorker {
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
}
