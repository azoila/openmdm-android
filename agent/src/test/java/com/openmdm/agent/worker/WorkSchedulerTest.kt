package com.openmdm.agent.worker

import androidx.work.*
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [WorkScheduler].
 *
 * Tests work scheduling for heartbeats, commands, push tokens, and pending command sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var workScheduler: WorkScheduler

    @Before
    fun setup() {
        workManager = mockk(relaxed = true)
        workScheduler = WorkScheduler(workManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ============================================
    // Heartbeat Scheduling Tests
    // ============================================

    @Test
    fun `scheduleHeartbeat enqueues periodic work`() {
        workScheduler.scheduleHeartbeat()

        verify {
            workManager.enqueueUniquePeriodicWork(
                HeartbeatWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                any<PeriodicWorkRequest>()
            )
        }
    }

    @Test
    fun `scheduleHeartbeat uses default interval when not specified`() {
        val capturedRequest = slot<PeriodicWorkRequest>()

        every {
            workManager.enqueueUniquePeriodicWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.scheduleHeartbeat()

        // Default interval should be 15 minutes (WorkManager minimum)
        assertThat(capturedRequest.captured.workSpec.intervalDuration)
            .isEqualTo(TimeUnit.MINUTES.toMillis(15))
    }

    @Test
    fun `scheduleHeartbeat enforces minimum 15 minute interval`() {
        val capturedRequest = slot<PeriodicWorkRequest>()

        every {
            workManager.enqueueUniquePeriodicWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        // Try to schedule with 1 minute interval (below minimum)
        workScheduler.scheduleHeartbeat(intervalMinutes = 1)

        // Should be coerced to minimum of 15 minutes
        assertThat(capturedRequest.captured.workSpec.intervalDuration)
            .isEqualTo(TimeUnit.MINUTES.toMillis(15))
    }

    @Test
    fun `scheduleHeartbeat accepts intervals above minimum`() {
        val capturedRequest = slot<PeriodicWorkRequest>()

        every {
            workManager.enqueueUniquePeriodicWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.scheduleHeartbeat(intervalMinutes = 30)

        assertThat(capturedRequest.captured.workSpec.intervalDuration)
            .isEqualTo(TimeUnit.MINUTES.toMillis(30))
    }

    @Test
    fun `scheduleHeartbeat requires network connectivity`() {
        val capturedRequest = slot<PeriodicWorkRequest>()

        every {
            workManager.enqueueUniquePeriodicWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.scheduleHeartbeat()

        assertThat(capturedRequest.captured.workSpec.constraints.requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun `scheduleHeartbeat uses exponential backoff`() {
        val capturedRequest = slot<PeriodicWorkRequest>()

        every {
            workManager.enqueueUniquePeriodicWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.scheduleHeartbeat()

        assertThat(capturedRequest.captured.workSpec.backoffPolicy)
            .isEqualTo(BackoffPolicy.EXPONENTIAL)
    }

    @Test
    fun `cancelHeartbeat cancels unique work`() {
        workScheduler.cancelHeartbeat()

        verify { workManager.cancelUniqueWork(HeartbeatWorker.WORK_NAME) }
    }

    // ============================================
    // Immediate Heartbeat Tests
    // ============================================

    @Test
    fun `triggerImmediateHeartbeat enqueues one-time work`() {
        workScheduler.triggerImmediateHeartbeat()

        verify { workManager.enqueue(any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `triggerImmediateHeartbeat requires network connectivity`() {
        val capturedRequest = slot<OneTimeWorkRequest>()

        every { workManager.enqueue(capture(capturedRequest)) } returns mockk()

        workScheduler.triggerImmediateHeartbeat()

        assertThat(capturedRequest.captured.workSpec.constraints.requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun `triggerImmediateHeartbeat has immediate_heartbeat tag`() {
        val capturedRequest = slot<OneTimeWorkRequest>()

        every { workManager.enqueue(capture(capturedRequest)) } returns mockk()

        workScheduler.triggerImmediateHeartbeat()

        assertThat(capturedRequest.captured.tags).contains("immediate_heartbeat")
    }

    // ============================================
    // Command Scheduling Tests
    // ============================================

    @Test
    fun `scheduleCommand enqueues unique one-time work`() {
        val commandId = "cmd-123"
        val commandType = "installApp"
        val payloadJson = """{"url":"https://example.com/app.apk"}"""

        workScheduler.scheduleCommand(commandId, commandType, payloadJson)

        verify {
            workManager.enqueueUniqueWork(
                "${CommandWorker.WORK_NAME_PREFIX}$commandId",
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `scheduleCommand uses KEEP policy to prevent duplicates`() {
        val capturedPolicy = slot<ExistingWorkPolicy>()

        every {
            workManager.enqueueUniqueWork(any(), capture(capturedPolicy), any<OneTimeWorkRequest>())
        } returns mockk()

        workScheduler.scheduleCommand("cmd-123", "installApp", null)

        assertThat(capturedPolicy.captured).isEqualTo(ExistingWorkPolicy.KEEP)
    }

    @Test
    fun `scheduleCommand sets correct input data`() {
        val capturedRequest = slot<OneTimeWorkRequest>()
        val commandId = "cmd-123"
        val commandType = "installApp"
        val payloadJson = """{"url":"https://example.com/app.apk"}"""

        every {
            workManager.enqueueUniqueWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.scheduleCommand(commandId, commandType, payloadJson)

        val inputData = capturedRequest.captured.workSpec.input
        assertThat(inputData.getString(CommandWorker.KEY_COMMAND_ID)).isEqualTo(commandId)
        assertThat(inputData.getString(CommandWorker.KEY_COMMAND_TYPE)).isEqualTo(commandType)
        assertThat(inputData.getString(CommandWorker.KEY_PAYLOAD_JSON)).isEqualTo(payloadJson)
    }

    @Test
    fun `scheduleCommand handles null payload`() {
        val capturedRequest = slot<OneTimeWorkRequest>()

        every {
            workManager.enqueueUniqueWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.scheduleCommand("cmd-123", "lock", null)

        val inputData = capturedRequest.captured.workSpec.input
        assertThat(inputData.getString(CommandWorker.KEY_PAYLOAD_JSON)).isNull()
    }

    @Test
    fun `scheduleCommand requires network connectivity`() {
        val capturedRequest = slot<OneTimeWorkRequest>()

        every {
            workManager.enqueueUniqueWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.scheduleCommand("cmd-123", "installApp", null)

        assertThat(capturedRequest.captured.workSpec.constraints.requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun `scheduleCommand adds command tag`() {
        val capturedRequest = slot<OneTimeWorkRequest>()
        val commandId = "cmd-123"

        every {
            workManager.enqueueUniqueWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.scheduleCommand(commandId, "installApp", null)

        assertThat(capturedRequest.captured.tags).contains("command_$commandId")
    }

    // ============================================
    // Push Token Registration Tests
    // ============================================

    @Test
    fun `schedulePushTokenRegistration enqueues unique work`() {
        val fcmToken = "fcm-token-12345"

        workScheduler.schedulePushTokenRegistration(fcmToken)

        verify {
            workManager.enqueueUniqueWork(
                PushTokenWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `schedulePushTokenRegistration uses REPLACE policy`() {
        val capturedPolicy = slot<ExistingWorkPolicy>()

        every {
            workManager.enqueueUniqueWork(any(), capture(capturedPolicy), any<OneTimeWorkRequest>())
        } returns mockk()

        workScheduler.schedulePushTokenRegistration("fcm-token")

        // REPLACE ensures new token registration supersedes old one
        assertThat(capturedPolicy.captured).isEqualTo(ExistingWorkPolicy.REPLACE)
    }

    @Test
    fun `schedulePushTokenRegistration sets FCM token in input data`() {
        val capturedRequest = slot<OneTimeWorkRequest>()
        val fcmToken = "fcm-token-12345"

        every {
            workManager.enqueueUniqueWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.schedulePushTokenRegistration(fcmToken)

        val inputData = capturedRequest.captured.workSpec.input
        assertThat(inputData.getString(PushTokenWorker.KEY_FCM_TOKEN)).isEqualTo(fcmToken)
    }

    @Test
    fun `schedulePushTokenRegistration requires network connectivity`() {
        val capturedRequest = slot<OneTimeWorkRequest>()

        every {
            workManager.enqueueUniqueWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.schedulePushTokenRegistration("fcm-token")

        assertThat(capturedRequest.captured.workSpec.constraints.requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
    }

    // ============================================
    // Sync Pending Commands Tests
    // ============================================

    @Test
    fun `syncPendingCommands enqueues unique work`() {
        workScheduler.syncPendingCommands()

        verify {
            workManager.enqueueUniqueWork(
                SyncPendingCommandsWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `syncPendingCommands requires network connectivity`() {
        val capturedRequest = slot<OneTimeWorkRequest>()

        every {
            workManager.enqueueUniqueWork(any(), any(), capture(capturedRequest))
        } returns mockk()

        workScheduler.syncPendingCommands()

        assertThat(capturedRequest.captured.workSpec.constraints.requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
    }

    // ============================================
    // Work Status Tests
    // ============================================

    @Test
    fun `isHeartbeatScheduled returns true when work is enqueued`() = runTest {
        val workInfo = mockk<WorkInfo>()
        every { workInfo.state } returns WorkInfo.State.ENQUEUED

        val future = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns listOf(workInfo)
        every { workManager.getWorkInfosForUniqueWork(HeartbeatWorker.WORK_NAME) } returns future

        val result = workScheduler.isHeartbeatScheduled()

        assertThat(result).isTrue()
    }

    @Test
    fun `isHeartbeatScheduled returns true when work is running`() = runTest {
        val workInfo = mockk<WorkInfo>()
        every { workInfo.state } returns WorkInfo.State.RUNNING

        val future = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns listOf(workInfo)
        every { workManager.getWorkInfosForUniqueWork(HeartbeatWorker.WORK_NAME) } returns future

        val result = workScheduler.isHeartbeatScheduled()

        assertThat(result).isTrue()
    }

    @Test
    fun `isHeartbeatScheduled returns false when work is completed`() = runTest {
        val workInfo = mockk<WorkInfo>()
        every { workInfo.state } returns WorkInfo.State.SUCCEEDED

        val future = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns listOf(workInfo)
        every { workManager.getWorkInfosForUniqueWork(HeartbeatWorker.WORK_NAME) } returns future

        val result = workScheduler.isHeartbeatScheduled()

        assertThat(result).isFalse()
    }

    @Test
    fun `isHeartbeatScheduled returns false when no work exists`() = runTest {
        val future = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns emptyList()
        every { workManager.getWorkInfosForUniqueWork(HeartbeatWorker.WORK_NAME) } returns future

        val result = workScheduler.isHeartbeatScheduled()

        assertThat(result).isFalse()
    }

    @Test
    fun `isHeartbeatScheduled returns false on exception`() = runTest {
        val future = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { future.get() } throws RuntimeException("Error")
        every { workManager.getWorkInfosForUniqueWork(HeartbeatWorker.WORK_NAME) } returns future

        val result = workScheduler.isHeartbeatScheduled()

        assertThat(result).isFalse()
    }

    // ============================================
    // Cancel All Work Tests
    // ============================================

    @Test
    fun `cancelAllWork cancels heartbeat work`() {
        workScheduler.cancelAllWork()

        verify { workManager.cancelAllWorkByTag(HeartbeatWorker.WORK_NAME) }
    }

    @Test
    fun `cancelAllWork cancels push token work`() {
        workScheduler.cancelAllWork()

        verify { workManager.cancelAllWorkByTag(PushTokenWorker.WORK_NAME) }
    }

    @Test
    fun `cancelAllWork cancels sync pending commands work`() {
        workScheduler.cancelAllWork()

        verify { workManager.cancelAllWorkByTag(SyncPendingCommandsWorker.WORK_NAME) }
    }

    // ============================================
    // Constants Tests
    // ============================================

    @Test
    fun `default heartbeat interval is 15 minutes`() {
        assertThat(WorkScheduler.DEFAULT_HEARTBEAT_INTERVAL_MINUTES).isEqualTo(15L)
    }

    @Test
    fun `minimum heartbeat interval is 15 minutes`() {
        assertThat(WorkScheduler.MIN_HEARTBEAT_INTERVAL_MINUTES).isEqualTo(15L)
    }
}
