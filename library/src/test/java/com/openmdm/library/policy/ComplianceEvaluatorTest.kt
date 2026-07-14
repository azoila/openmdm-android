package com.openmdm.library.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Compliance enforcement.
 *
 * `PolicySettings` carried these rules — minimum OS version, encryption required,
 * screen lock required — all along, and nothing read them. An operator could set
 * "require encryption", watch it save, and manage an unencrypted fleet forever.
 * Declarative intent without an enforcer is worse than no intent, because it
 * looks enforced. These tests are the enforcer's contract.
 */
class ComplianceEvaluatorTest {

    private fun state(
        os: String = "13",
        encrypted: Boolean = true,
        screenLock: Boolean = true,
        rooted: Boolean = false,
    ) = DeviceComplianceState(os, encrypted, screenLock, rooted)

    @Test
    fun `a device meeting every rule is compliant`() {
        val result = ComplianceEvaluator.evaluate(
            PolicySettings(encryptionRequired = true, screenLockRequired = true),
            state(),
        )
        assertThat(result.compliant).isTrue()
        assertThat(result.violations).isEmpty()
    }

    @Test
    fun `flags an OS below the minimum`() {
        val result = ComplianceEvaluator.evaluate(
            PolicySettings(minimumOsVersion = "12"),
            state(os = "11"),
        )
        assertThat(result.compliant).isFalse()
        assertThat(result.codes).contains("OS_VERSION_TOO_LOW")
    }

    @Test
    fun `does not flag a modern OS as below the minimum`() {
        // The classic string-compare bug: "10" < "9" lexically, so a naive check
        // reports Android 10 as older than Android 9.
        val result = ComplianceEvaluator.evaluate(
            PolicySettings(minimumOsVersion = "9"),
            state(os = "10"),
        )
        assertThat(result.compliant).isTrue()
    }

    @Test
    fun `flags a blocked OS version`() {
        val result = ComplianceEvaluator.evaluate(
            PolicySettings(blockedOsVersions = listOf("13")),
            state(os = "13"),
        )
        assertThat(result.codes).contains("OS_VERSION_BLOCKED")
    }

    @Test
    fun `flags an unencrypted device when encryption is required`() {
        val result = ComplianceEvaluator.evaluate(
            PolicySettings(encryptionRequired = true),
            state(encrypted = false),
        )
        assertThat(result.codes).contains("NOT_ENCRYPTED")
    }

    @Test
    fun `does not flag encryption when the policy does not require it`() {
        val result = ComplianceEvaluator.evaluate(PolicySettings(), state(encrypted = false))
        assertThat(result.compliant).isTrue()
    }

    @Test
    fun `flags a missing screen lock when required`() {
        val result = ComplianceEvaluator.evaluate(
            PolicySettings(screenLockRequired = true),
            state(screenLock = false),
        )
        assertThat(result.codes).contains("NO_SCREEN_LOCK")
    }

    @Test
    fun `always flags a rooted device, policy or not`() {
        // A rooted device can defeat every other control on the list.
        val result = ComplianceEvaluator.evaluate(PolicySettings(), state(rooted = true))
        assertThat(result.codes).contains("ROOTED")
    }

    @Test
    fun `reports every violation, not just the first`() {
        val result = ComplianceEvaluator.evaluate(
            PolicySettings(
                minimumOsVersion = "14",
                encryptionRequired = true,
                screenLockRequired = true,
            ),
            state(os = "11", encrypted = false, screenLock = false, rooted = true),
        )
        // An operator needs the whole list to fix a device in one pass, not to
        // fix one thing and re-check to discover the next.
        assertThat(result.codes).containsExactly(
            "OS_VERSION_TOO_LOW",
            "NOT_ENCRYPTED",
            "NO_SCREEN_LOCK",
            "ROOTED",
        )
    }

    @Test
    fun `a preview OS build is treated as newer, not as unparseable-and-blocked`() {
        // A device running a preview build ("S") is not "below minimum 14".
        val result = ComplianceEvaluator.evaluate(
            PolicySettings(minimumOsVersion = "14"),
            state(os = "S"),
        )
        assertThat(result.compliant).isTrue()
    }
}
