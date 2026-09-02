package com.openmdm.agent.provisioning

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.openmdm.agent.data.ProvisioningStore
import com.openmdm.agent.worker.EnrollmentWorker
import com.openmdm.library.enrollment.EnrollmentConfig
import com.openmdm.library.enrollment.ManagedProvisioning
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig

/**
 * The handoff from a provisioning intent to a device that will enroll.
 *
 * The bug these pin: enrollment used to be queued from exactly one place, the
 * `PROFILE_PROVISIONING_COMPLETE` broadcast. On API 31+ that broadcast is sent
 * at the setup wizard's finalization step — so a wizard that aborted earlier
 * left a device that was Device Owner, had been handed a server URL in the
 * admin extras, and had queued nothing to go and use it.
 *
 * Adoption now happens from the policy-compliance activity too, which means it
 * can happen twice for one provisioning. So "twice is harmless" is part of the
 * contract, not an accident.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33])
class ProvisioningHandoffTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        ProvisioningStore(context).clear()
    }

    private fun provisioningIntent(config: EnrollmentConfig): Intent =
        ManagedProvisioning.buildProvisioningIntent(
            android.content.ComponentName("com.openmdm.agent", "com.openmdm.agent.Receiver"),
            config,
        )

    private fun enrollmentWork(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(EnrollmentWorker.WORK_NAME)
            .get()

    @Test
    fun `adopts the server url and queues enrollment`() {
        val config = EnrollmentConfig(
            serverUrl = "https://mdm.example.com",
            enrollmentToken = "PAIR-1234",
            policyId = "kiosk",
        )

        val adopted = ProvisioningHandoff.adopt(
            context,
            provisioningIntent(config),
            ProvisioningHandoff.SOURCE_POLICY_COMPLIANCE,
        )

        assertThat(adopted?.serverUrl).isEqualTo("https://mdm.example.com")

        val store = ProvisioningStore(context)
        assertThat(store.serverUrl).isEqualTo("https://mdm.example.com")
        assertThat(store.enrollmentToken).isEqualTo("PAIR-1234")
        assertThat(store.policyId).isEqualTo("kiosk")

        assertThat(enrollmentWork()).hasSize(1)
    }

    @Test
    fun `adopting twice queues a single enrollment`() {
        val intent = provisioningIntent(EnrollmentConfig(serverUrl = "https://mdm.example.com"))

        // Both provisioning intents carry the same bundle; on a healthy device
        // the policy-compliance activity and the broadcast both fire.
        ProvisioningHandoff.adopt(context, intent, ProvisioningHandoff.SOURCE_POLICY_COMPLIANCE)
        ProvisioningHandoff.adopt(context, intent, ProvisioningHandoff.SOURCE_PROVISIONING_COMPLETE)

        assertThat(enrollmentWork()).hasSize(1)
    }

    @Test
    fun `an intent with no openmdm extras adopts nothing and queues nothing`() {
        // A device provisioned as Device Owner without OpenMDM extras: legitimate,
        // but there is no server to enroll against, so queueing work would only
        // retry forever against a build-time default nobody asked for.
        val adopted = ProvisioningHandoff.adopt(
            context,
            Intent(),
            ProvisioningHandoff.SOURCE_PROVISIONING_COMPLETE,
        )

        assertThat(adopted).isNull()
        assertThat(ProvisioningStore(context).isProvisioned).isFalse()
        assertThat(enrollmentWork()).isEmpty()
    }

    @Test
    fun `a failure to queue never propagates to the provisioning caller`() {
        // The callers sit on the platform's provisioning critical path: throwing
        // out of PolicyComplianceActivity.onCreate fails the whole provisioning,
        // trading a recoverable enrollment miss for an unrecoverable one.
        val adopted = ProvisioningHandoff.adopt(
            NoWorkManagerContext(context),
            provisioningIntent(EnrollmentConfig(serverUrl = "https://mdm.example.com")),
            ProvisioningHandoff.SOURCE_POLICY_COMPLIANCE,
        )

        // Still reports what provisioning handed us: "could not queue" and "was
        // never told a server" are different bugs and must read differently in
        // the logs.
        assertThat(adopted?.serverUrl).isEqualTo("https://mdm.example.com")
    }

    /** A device where reaching WorkManager blows up, rather than merely failing. */
    private class NoWorkManagerContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context =
            throw IllegalStateException("WorkManager is not initialised")
    }
}
