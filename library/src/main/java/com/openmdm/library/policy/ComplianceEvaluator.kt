package com.openmdm.library.policy

import android.os.Build

/**
 * Why a device does not meet its policy.
 *
 * Each reason names the rule and what the device actually looks like, because
 * "device is non-compliant" is not something an operator can act on.
 */
sealed class ComplianceViolation(val code: String, val detail: String) {

    class OsVersionTooLow(current: String, minimum: String) : ComplianceViolation(
        "OS_VERSION_TOO_LOW",
        "OS $current is below the required minimum $minimum",
    )

    class OsVersionTooHigh(current: String, maximum: String) : ComplianceViolation(
        "OS_VERSION_TOO_HIGH",
        "OS $current is above the permitted maximum $maximum",
    )

    class OsVersionBlocked(current: String) : ComplianceViolation(
        "OS_VERSION_BLOCKED",
        "OS $current is explicitly blocked",
    )

    object NotEncrypted : ComplianceViolation(
        "NOT_ENCRYPTED",
        "Device storage is not encrypted, and the policy requires it",
    )

    object NoScreenLock : ComplianceViolation(
        "NO_SCREEN_LOCK",
        "No screen lock is set, and the policy requires one",
    )

    object Rooted : ComplianceViolation(
        "ROOTED",
        "Device appears to be rooted",
    )
}

/** What the device actually is, as observed at evaluation time. */
data class DeviceComplianceState(
    val osVersion: String = Build.VERSION.RELEASE ?: "0",
    val isEncrypted: Boolean = false,
    val hasScreenLock: Boolean = false,
    val isRooted: Boolean = false,
)

data class ComplianceResult(
    val compliant: Boolean,
    val violations: List<ComplianceViolation>,
) {
    val codes: List<String> get() = violations.map { it.code }
}

/**
 * Evaluates a device against the compliance rules its policy declares.
 *
 * `PolicySettings` has carried `minimumOsVersion`, `maximumOsVersion`,
 * `blockedOsVersions`, `encryptionRequired` and `screenLockRequired` all along —
 * and **nothing ever read them**. They were parsed off the wire, mapped into a
 * typed object, and then dropped: an operator could set "require encryption",
 * see it saved, and manage an unencrypted fleet forever.
 *
 * Declarative intent without an enforcer is worse than no intent at all, because
 * it *looks* enforced. This is the enforcer.
 *
 * Deliberately pure: no Android APIs, no DevicePolicyManager. The device state is
 * passed in. That is what makes the rules testable — the reason they went
 * unenforced for so long is that everything around them could only run on real
 * hardware.
 */
object ComplianceEvaluator {

    fun evaluate(policy: PolicySettings, state: DeviceComplianceState): ComplianceResult {
        val violations = mutableListOf<ComplianceViolation>()

        policy.minimumOsVersion?.let { minimum ->
            if (compareOsVersions(state.osVersion, minimum) < 0) {
                violations += ComplianceViolation.OsVersionTooLow(state.osVersion, minimum)
            }
        }

        policy.maximumOsVersion?.let { maximum ->
            if (compareOsVersions(state.osVersion, maximum) > 0) {
                violations += ComplianceViolation.OsVersionTooHigh(state.osVersion, maximum)
            }
        }

        if (policy.blockedOsVersions.any { it == state.osVersion }) {
            violations += ComplianceViolation.OsVersionBlocked(state.osVersion)
        }

        if (policy.encryptionRequired && !state.isEncrypted) {
            violations += ComplianceViolation.NotEncrypted
        }

        if (policy.screenLockRequired && !state.hasScreenLock) {
            violations += ComplianceViolation.NoScreenLock
        }

        if (state.isRooted) {
            // Not policy-gated: a rooted device can defeat every other control on
            // this list, so it is always worth reporting.
            violations += ComplianceViolation.Rooted
        }

        return ComplianceResult(compliant = violations.isEmpty(), violations = violations)
    }

    /**
     * Compare Android version strings numerically.
     *
     * "10" vs "9" is the trap: lexically "10" < "9", so a naive string compare
     * concludes Android 10 is older than Android 9 and happily reports a modern
     * device as below the minimum. A non-numeric part (a preview build like "S")
     * sorts as *newer*, because refusing to manage a device for running a newer
     * OS than we recognise is the wrong default.
     */
    internal fun compareOsVersions(a: String, b: String): Int {
        val left = a.split(".")
        val right = b.split(".")

        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)?.toIntOrNull()
            val y = right.getOrNull(i)?.toIntOrNull()

            when {
                x == null && y == null -> continue
                x == null -> return 1  // unparseable (preview build) reads as newer
                y == null -> return -1
                x != y -> return x.compareTo(y)
            }
        }
        return 0
    }
}
