package com.openmdm.agent.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.openmdm.agent.data.*
import com.openmdm.agent.data.local.dao.CommandDao
import com.openmdm.agent.data.local.entity.CommandEntity
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
 * Unit tests for [CommandWorker].
 *
 * Tests command execution, retry logic, acknowledgment, and failure handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommandWorkerTest {

    private lateinit var context: Context
    private lateinit var mdmApi: MDMApi
    private lateinit var mdmRepository: MDMRepository
    private lateinit var commandDao: CommandDao

    private val testToken = "test-jwt-token"
    private val testCommandId = "cmd-123"
    private val testCommandType = "installApp"
    private val testPayloadJson = """{"url":"https://example.com/app.apk","packageName":"com.test.app"}"""

    private val enrolledState = EnrollmentState(
        isEnrolled = true,
        deviceId = "device-123",
        enrollmentId = "enroll-456",
        token = testToken,
        refreshToken = "refresh-token",
        serverUrl = "https://api.test.com",
        policyVersion = "1.0"
    )

    private val notEnrolledState = EnrollmentState(isEnrolled = false)

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        mdmApi = mockk(relaxed = true)
        mdmRepository = mockk(relaxed = true)
        commandDao = mockk(relaxed = true)

        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState
        coEvery { mdmApi.acknowledgeCommand(any(), any()) } returns Response.success(Unit)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ============================================
    // Input Validation Tests
    // ============================================

    @Test
    fun `returns failure when command_id is missing`() = runTest {
        val worker = createWorker(
            commandId = null,
            commandType = testCommandType,
            payloadJson = testPayloadJson
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `returns failure when command_type is missing`() = runTest {
        val worker = createWorker(
            commandId = testCommandId,
            commandType = null,
            payloadJson = testPayloadJson
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `succeeds when payload_json is null`() = runTest {
        val worker = createWorker(
            commandId = testCommandId,
            commandType = "lock", // Lock command doesn't need payload
            payloadJson = null
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ============================================
    // Enrollment Validation Tests
    // ============================================

    @Test
    fun `returns failure when device not enrolled`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns notEnrolledState

        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify { commandDao.markFailed(testCommandId, "Device not enrolled", any()) }
    }

    @Test
    fun `returns failure when token is null`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } returns enrolledState.copy(token = null)

        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify { commandDao.markFailed(testCommandId, "Device not enrolled", any()) }
    }

    // ============================================
    // Command Acknowledgment Tests
    // ============================================

    @Test
    fun `acknowledges command before execution`() = runTest {
        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson
        )

        worker.doWork()

        coVerify { mdmApi.acknowledgeCommand("Bearer $testToken", testCommandId) }
    }

    @Test
    fun `continues execution even if acknowledgment fails`() = runTest {
        coEvery { mdmApi.acknowledgeCommand(any(), any()) } throws IOException("Network error")

        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson
        )

        val result = worker.doWork()

        // Should still succeed even if ack fails
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify { commandDao.markCompleted(testCommandId, any()) }
    }

    // ============================================
    // Successful Execution Tests
    // ============================================

    @Test
    fun `marks command in progress at start`() = runTest {
        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson
        )

        worker.doWork()

        coVerify { commandDao.markInProgress(testCommandId, any()) }
    }

    @Test
    fun `marks command completed on success`() = runTest {
        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify { commandDao.markCompleted(testCommandId, any()) }
    }

    @Test
    fun `executes installApp command successfully`() = runTest {
        val worker = createWorker(
            commandId = testCommandId,
            commandType = "installApp",
            payloadJson = """{"url":"https://example.com/app.apk"}"""
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `executes lock command successfully`() = runTest {
        val worker = createWorker(
            commandId = testCommandId,
            commandType = "lock",
            payloadJson = null
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ============================================
    // Retry Logic Tests
    // ============================================

    @Test
    fun `retries on IOException when under max retries`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } throws IOException("Network error")

        val worker = createWorkerWithAttempt(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson,
            runAttemptCount = 0
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `retries on generic exception when under max retries`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } throws RuntimeException("Unexpected error")

        val worker = createWorkerWithAttempt(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson,
            runAttemptCount = 2
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `updates database status on retry`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } throws IOException("Network error")

        val worker = createWorkerWithAttempt(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson,
            runAttemptCount = 1
        )

        worker.doWork()

        coVerify {
            commandDao.updateCommandStatus(
                id = testCommandId,
                status = CommandEntity.STATUS_PENDING,
                timestamp = any(),
                error = "Network error"
            )
        }
    }

    // ============================================
    // Max Retries Exceeded Tests
    // ============================================

    @Test
    fun `fails after max retries exceeded`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } throws IOException("Network error")

        val worker = createWorkerWithAttempt(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson,
            runAttemptCount = CommandWorker.MAX_RETRIES
        )

        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }

    @Test
    fun `marks command as failed after max retries`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } throws IOException("Network error")

        val worker = createWorkerWithAttempt(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson,
            runAttemptCount = CommandWorker.MAX_RETRIES
        )

        worker.doWork()

        coVerify { commandDao.markFailed(testCommandId, "Network error", any()) }
    }

    @Test
    fun `reports failure to server after max retries`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } throws IOException("Network error") andThen enrolledState

        val worker = createWorkerWithAttempt(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson,
            runAttemptCount = CommandWorker.MAX_RETRIES
        )

        worker.doWork()

        coVerify {
            mdmApi.failCommand(
                "Bearer $testToken",
                testCommandId,
                match<CommandErrorRequest> { it.error == "Network error" }
            )
        }
    }

    @Test
    fun `failure result contains command id and error`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } throws IOException("Test error")

        val worker = createWorkerWithAttempt(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson,
            runAttemptCount = CommandWorker.MAX_RETRIES
        )

        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        val failure = result as ListenableWorker.Result.Failure
        assertThat(failure.outputData.getString("command_id")).isEqualTo(testCommandId)
        assertThat(failure.outputData.getString("error")).isEqualTo("Test error")
    }

    // ============================================
    // Payload Parsing Tests
    // ============================================

    @Test
    fun `parses valid JSON payload`() = runTest {
        val payloadJson = """{"url":"https://example.com/app.apk","version":"1.0"}"""

        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = payloadJson
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `handles invalid JSON payload gracefully`() = runTest {
        val invalidJson = "not valid json {"

        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = invalidJson
        )

        // Should not crash, payload will be null
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `handles empty JSON payload`() = runTest {
        val worker = createWorker(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = "{}"
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ============================================
    // Critical Commands Tests
    // ============================================

    @Test
    fun `critical commands set is defined correctly`() {
        assertThat(CommandWorker.CRITICAL_COMMANDS).containsExactly(
            "wipe", "lock", "factoryReset", "shutdown", "reboot"
        )
    }

    @Test
    fun `wipe is a critical command`() {
        assertThat(CommandWorker.CRITICAL_COMMANDS).contains("wipe")
    }

    @Test
    fun `installApp is not a critical command`() {
        assertThat(CommandWorker.CRITICAL_COMMANDS).doesNotContain("installApp")
    }

    // ============================================
    // Server Failure Reporting Tests
    // ============================================

    @Test
    fun `continues even if server failure report fails`() = runTest {
        coEvery { mdmRepository.getEnrollmentState() } throws IOException("Network error") andThen enrolledState
        coEvery { mdmApi.failCommand(any(), any(), any()) } throws IOException("Report failed")

        val worker = createWorkerWithAttempt(
            commandId = testCommandId,
            commandType = testCommandType,
            payloadJson = testPayloadJson,
            runAttemptCount = CommandWorker.MAX_RETRIES
        )

        // Should not throw, just log the error
        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }

    // ============================================
    // Constants Tests
    // ============================================

    @Test
    fun `max retries is 5`() {
        assertThat(CommandWorker.MAX_RETRIES).isEqualTo(5)
    }

    @Test
    fun `work name prefix is correct`() {
        assertThat(CommandWorker.WORK_NAME_PREFIX).isEqualTo("command_")
    }

    @Test
    fun `input data keys are correct`() {
        assertThat(CommandWorker.KEY_COMMAND_ID).isEqualTo("command_id")
        assertThat(CommandWorker.KEY_COMMAND_TYPE).isEqualTo("command_type")
        assertThat(CommandWorker.KEY_PAYLOAD_JSON).isEqualTo("payload_json")
    }

    // ============================================
    // Helper Methods
    // ============================================

    private fun createWorker(
        commandId: String?,
        commandType: String?,
        payloadJson: String?
    ): CommandWorker {
        val inputData = workDataOf(
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

    private fun createWorkerWithAttempt(
        commandId: String?,
        commandType: String?,
        payloadJson: String?,
        runAttemptCount: Int
    ): CommandWorker {
        val inputData = workDataOf(
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
