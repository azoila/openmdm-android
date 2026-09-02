package com.openmdm.agent.provisioning

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openmdm.agent.data.ProvisioningStore
import com.openmdm.agent.worker.WorkScheduler
import com.openmdm.library.enrollment.EnrollmentConfig
import com.openmdm.library.enrollment.ManagedProvisioning
import com.openmdm.library.telemetry.MdmTelemetryHolder

/**
 * The one place a provisioning intent turns into a device that will enroll.
 *
 * ## Why this is not just a method on the receiver
 *
 * The admin extras bundle — server URL, enrollment token, policy id — rides on
 * *three* provisioning intents: `GET_PROVISIONING_MODE`, `ADMIN_POLICY_COMPLIANCE`
 * and the `PROFILE_PROVISIONING_COMPLETE` broadcast. Until now only the broadcast
 * was consumed, which made the whole post-provisioning enrollment depend on a
 * single delivery.
 *
 * That is a single point of failure with a very bad failure mode. On API 31+ the
 * broadcast is sent during the setup wizard's *finalization* step — after the
 * policy-compliance activity has already returned and the DPC is already Device
 * Owner. A setup wizard that aborts before finalization (the generic "Something
 * went wrong" screen) leaves a device that is fully managed, has no idea which
 * server it belongs to, and has nothing queued to find out. Managed, and
 * unmanageable.
 *
 * So both call sites now hand off through here. Adoption is idempotent by
 * construction — the store write is last-wins with the same values, and
 * [WorkScheduler.enqueueProvisioningEnrollment] uses `ExistingWorkPolicy.KEEP` —
 * so being called twice for one provisioning costs one redundant
 * SharedPreferences write and nothing else.
 *
 * ## Why nothing here is allowed to throw
 *
 * Both callers are on the platform's provisioning critical path: an exception in
 * `PolicyComplianceActivity.onCreate` fails the provisioning outright, and one in
 * the receiver kills a broadcast the system will not resend. Losing enrollment is
 * recoverable (the service re-arms it on the next start); failing the
 * provisioning is not.
 */
object ProvisioningHandoff {

    /** Adopted from the `ADMIN_POLICY_COMPLIANCE` activity, inside the setup wizard. */
    const val SOURCE_POLICY_COMPLIANCE = "policy_compliance"

    /** Adopted from the `PROFILE_PROVISIONING_COMPLETE` broadcast, at finalization. */
    const val SOURCE_PROVISIONING_COMPLETE = "provisioning_complete"

    /** Re-armed by [com.openmdm.agent.service.MDMService] on a later service start. */
    const val SOURCE_SERVICE_RECOVERY = "service_recovery"

    /**
     * Persist what provisioning told us and queue the enrollment that follows
     * from it.
     *
     * @return the adopted config, or `null` when the intent carried no OpenMDM
     *   server URL — the legitimate case for a device provisioned without our
     *   extras, which is Device Owner but must be enrolled by hand.
     */
    fun adopt(context: Context, intent: Intent, source: String): EnrollmentConfig? {
        val config = runCatching { ManagedProvisioning.extractConfig(intent) }
            .getOrElse { error ->
                Log.e(TAG, "[$source] could not read the admin extras bundle", error)
                MdmTelemetryHolder.nonFatal(error, "provisioning_extras_unreadable")
                null
            }

        if (config?.serverUrl == null) {
            Log.w(
                TAG,
                "[$source] provisioning carried no OpenMDM server URL; " +
                    "the device is managed but cannot enroll on its own",
            )
            return null
        }

        // Everything past here is best-effort. The config is returned either way,
        // so the caller's diagnostic log still reports what provisioning actually
        // handed us — a device that failed to *queue* enrollment is a different
        // problem from one that was never told where to enroll, and the logs must
        // not conflate them.
        runCatching {
            ProvisioningStore(context).save(config)

            Log.i(TAG, "[$source] provisioned for ${config.serverUrl}; scheduling enrollment")
            MdmTelemetryHolder.event(
                "provisioning_enrollment_scheduled",
                mapOf(
                    "source" to source,
                    "has_enrollment_token" to (config.enrollmentToken != null),
                    "has_policy_id" to (config.policyId != null),
                ),
            )

            WorkScheduler.enqueueProvisioningEnrollment(context)
        }.onFailure { error ->
            // Never propagate: see the class header. The service re-arms this on
            // its next start.
            Log.e(TAG, "[$source] failed to queue provisioning enrollment", error)
            MdmTelemetryHolder.nonFatal(error, "provisioning_handoff_failed")
        }

        return config
    }

    private const val TAG = "ProvisioningHandoff"
}
