package com.openmdm.library.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Update pre-flight checks.
 *
 * This logic used to live inside a 965-line `DeviceOwnerManager` in the agent,
 * behind `DevicePolicyManager` calls — so it could only run on a real device,
 * which is why it never ran in a test at all. Pulling it out is what makes the
 * downgrade protection something we can actually assert.
 */
class SecureUpdateTest {

    private fun params(
        target: String,
        targetCode: Int,
        minPrevious: String? = null,
    ) = SecureUpdateParams(
        apkUrl = "https://cdn/app.apk",
        packageName = "com.example.app",
        expectedSha256 = "abc",
        targetVersion = target,
        targetVersionCode = targetCode,
        minPreviousVersion = minPrevious,
    )

    // ----- version comparison -----

    @Test
    fun `orders versions numerically, not lexically`() {
        // The trap: "1.10.0" < "1.9.0" as strings, but 1.10 is NEWER.
        assertThat(SecureUpdate.compareVersions("1.10.0", "1.9.0")).isGreaterThan(0)
        assertThat(SecureUpdate.compareVersions("1.9.0", "1.10.0")).isLessThan(0)
    }

    @Test
    fun `treats missing trailing parts as equal`() {
        assertThat(SecureUpdate.compareVersions("1.2", "1.2.0")).isEqualTo(0)
    }

    @Test
    fun `an unparseable version is older, never equal`() {
        // Treating "1.2.0-rc" as "same as target" would mean the device is
        // silently never updated and nobody finds out.
        assertThat(SecureUpdate.compareVersions("1.2.0-rc", "1.2.0")).isNotEqualTo(0)
    }

    // ----- downgrade protection -----

    @Test
    fun `blocks installing an older build over a newer one`() {
        // How a fleet gets rolled backwards: a stale install command queued weeks
        // ago finally reaches a device that has since moved on.
        val rejection = SecureUpdate.rejectionReason(
            params(target = "1.0.0", targetCode = 10),
            currentVersion = "2.0.0",
            currentVersionCode = 20,
        )

        assertThat(rejection).isNotNull()
        assertThat(rejection!!.error).isEqualTo("DOWNGRADE_BLOCKED")
    }

    @Test
    fun `blocks re-installing the same version`() {
        val rejection = SecureUpdate.rejectionReason(
            params(target = "2.0.0", targetCode = 20),
            currentVersion = "2.0.0",
            currentVersionCode = 20,
        )

        assertThat(rejection?.error).isEqualTo("DOWNGRADE_BLOCKED")
    }

    @Test
    fun `allows a genuine upgrade`() {
        val rejection = SecureUpdate.rejectionReason(
            params(target = "2.0.0", targetCode = 20),
            currentVersion = "1.0.0",
            currentVersionCode = 10,
        )

        assertThat(rejection).isNull()
    }

    @Test
    fun `allows a first install`() {
        // Not installed reads as version code 0, so there is nothing to downgrade
        // from — a fresh device must still be able to receive the app.
        val rejection = SecureUpdate.rejectionReason(
            params(target = "1.0.0", targetCode = 10),
            currentVersion = "0.0.0",
            currentVersionCode = 0,
        )

        assertThat(rejection).isNull()
    }

    // ----- minimum previous version -----

    @Test
    fun `refuses an update that would skip a required migration`() {
        val rejection = SecureUpdate.rejectionReason(
            params(target = "3.0.0", targetCode = 30, minPrevious = "2.0.0"),
            currentVersion = "1.0.0",
            currentVersionCode = 10,
        )

        assertThat(rejection?.error).isEqualTo("MIN_VERSION_NOT_MET")
    }

    @Test
    fun `allows the update once the minimum is met`() {
        val rejection = SecureUpdate.rejectionReason(
            params(target = "3.0.0", targetCode = 30, minPrevious = "2.0.0"),
            currentVersion = "2.1.0",
            currentVersionCode = 21,
        )

        assertThat(rejection).isNull()
    }
}
