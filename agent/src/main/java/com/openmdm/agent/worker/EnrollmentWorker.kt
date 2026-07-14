package com.openmdm.agent.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.openmdm.agent.data.ProvisioningStore
import com.openmdm.agent.domain.repository.IEnrollmentRepository
import com.openmdm.agent.domain.usecase.EnrollDeviceUseCase
import com.openmdm.library.telemetry.MdmTelemetryHolder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Enrolls a device that was provisioned by QR, NFC, `afw#` or zero-touch.
 *
 * Runs out of [com.openmdm.agent.receiver.MDMDeviceAdminReceiver.onProfileProvisioningComplete],
 * because that callback has only a few seconds before the system may kill the
 * receiver — and enrollment means key generation, a challenge round-trip, and a
 * signed POST. Doing it inline would work on a fast network and fail silently on
 * a slow one, at the worst possible moment: the device is already Device Owner
 * and nobody is watching the screen.
 *
 * Retries with WorkManager's backoff. A device provisioned in a warehouse with no
 * signal must still enroll when it reaches somewhere with a network — a
 * provisioning flow that gives up on the first failure strands hardware.
 */
@HiltWorker
class EnrollmentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val enrollDevice: EnrollDeviceUseCase,
    private val enrollmentRepository: IEnrollmentRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Idempotent: a retry after a successful-but-unreported enroll must not
        // enroll twice.
        if (enrollmentRepository.getEnrollmentState().isEnrolled) {
            Log.i(TAG, "Already enrolled; nothing to do")
            return Result.success()
        }

        val store = ProvisioningStore(applicationContext)
        val serverUrl = store.serverUrl
        if (serverUrl == null) {
            // Nothing to enroll against, and no retry will change that.
            Log.w(TAG, "No provisioned server URL; giving up")
            return Result.failure()
        }

        // The pairing code the operator embedded in the QR payload. Without one,
        // the server must be configured to auto-enroll — otherwise there is
        // nothing to identify this device with.
        val token = store.enrollmentToken ?: ""

        return enrollDevice(token).fold(
            onSuccess = {
                Log.i(TAG, "Enrolled via provisioning against $serverUrl")
                MdmTelemetryHolder.event("provisioning_enrollment_succeeded")
                Result.success()
            },
            onFailure = { error ->
                Log.w(TAG, "Provisioning enrollment failed: ${error.message}", error)
                MdmTelemetryHolder.nonFatal(error, "provisioning_enrollment_failed")
                // Retry: a device provisioned out of network range must still
                // enroll once it has a network.
                Result.retry()
            },
        )
    }

    companion object {
        const val WORK_NAME = "openmdm_provisioning_enrollment"
        private const val TAG = "EnrollmentWorker"
    }
}
