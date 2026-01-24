package com.openmdm.agent.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [EnrollmentState] data class and related models.
 *
 * Note: MDMRepository uses DataStore which requires special test setup.
 * These tests focus on the data class behavior and can be extended
 * for integration tests with actual DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MDMRepositoryTest {

    // ============================================
    // EnrollmentState Data Class Tests
    // ============================================

    @Test
    fun `EnrollmentState defaults are correct`() {
        val state = EnrollmentState()

        assertThat(state.isEnrolled).isFalse()
        assertThat(state.deviceId).isNull()
        assertThat(state.enrollmentId).isNull()
        assertThat(state.token).isNull()
        assertThat(state.refreshToken).isNull()
        assertThat(state.policyVersion).isNull()
        assertThat(state.lastSync).isNull()
    }

    @Test
    fun `EnrollmentState with deviceId is enrolled`() {
        val enrolledState = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            enrollmentId = "enroll-456",
            token = "jwt-token",
            refreshToken = "refresh-token",
            serverUrl = "https://api.test.com",
            policyVersion = "1.0",
            lastSync = System.currentTimeMillis()
        )

        assertThat(enrolledState.isEnrolled).isTrue()
        assertThat(enrolledState.deviceId).isEqualTo("device-123")
        assertThat(enrolledState.enrollmentId).isEqualTo("enroll-456")
        assertThat(enrolledState.token).isEqualTo("jwt-token")
        assertThat(enrolledState.refreshToken).isEqualTo("refresh-token")
        assertThat(enrolledState.policyVersion).isEqualTo("1.0")
    }

    @Test
    fun `EnrollmentState copy works correctly`() {
        val original = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            token = "old-token"
        )

        val updated = original.copy(token = "new-token")

        assertThat(updated.deviceId).isEqualTo("device-123")
        assertThat(updated.token).isEqualTo("new-token")
        assertThat(original.token).isEqualTo("old-token")
    }

    @Test
    fun `EnrollmentState without token`() {
        val state = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            token = null
        )

        assertThat(state.isEnrolled).isTrue()
        assertThat(state.token).isNull()
    }

    @Test
    fun `EnrollmentState serverUrl has default value`() {
        val state = EnrollmentState()

        // Should have the default server URL from BuildConfig
        assertThat(state.serverUrl).isNotEmpty()
    }

    @Test
    fun `EnrollmentState equality works correctly`() {
        val state1 = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            token = "token"
        )

        val state2 = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            token = "token"
        )

        val state3 = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-456",
            token = "token"
        )

        assertThat(state1).isEqualTo(state2)
        assertThat(state1).isNotEqualTo(state3)
    }

    // ============================================
    // Token Expiration Scenario Tests
    // ============================================

    @Test
    fun `EnrollmentState can represent token expiry scenario`() {
        // Scenario: Device is enrolled but token is expired
        val expiredState = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            token = null, // Token cleared after expiry
            refreshToken = "refresh-token" // Still has refresh token
        )

        assertThat(expiredState.isEnrolled).isTrue()
        assertThat(expiredState.token).isNull()
        assertThat(expiredState.refreshToken).isNotNull()
    }

    @Test
    fun `EnrollmentState can represent re-enrollment needed scenario`() {
        // Scenario: Both tokens expired, device needs re-enrollment
        val needsReenrollment = EnrollmentState(
            isEnrolled = false,
            deviceId = null,
            token = null,
            refreshToken = null
        )

        assertThat(needsReenrollment.isEnrolled).isFalse()
        assertThat(needsReenrollment.deviceId).isNull()
        assertThat(needsReenrollment.token).isNull()
        assertThat(needsReenrollment.refreshToken).isNull()
    }

    // ============================================
    // Policy Update Scenario Tests
    // ============================================

    @Test
    fun `EnrollmentState with policy version`() {
        val state = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            policyVersion = "2.0"
        )

        assertThat(state.policyVersion).isEqualTo("2.0")
    }

    @Test
    fun `EnrollmentState policy version update via copy`() {
        val original = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            policyVersion = "1.0"
        )

        val updated = original.copy(policyVersion = "2.0")

        assertThat(original.policyVersion).isEqualTo("1.0")
        assertThat(updated.policyVersion).isEqualTo("2.0")
    }

    // ============================================
    // Last Sync Tests
    // ============================================

    @Test
    fun `EnrollmentState with lastSync timestamp`() {
        val timestamp = System.currentTimeMillis()
        val state = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            lastSync = timestamp
        )

        assertThat(state.lastSync).isEqualTo(timestamp)
    }

    @Test
    fun `EnrollmentState lastSync can be updated`() {
        val oldTimestamp = System.currentTimeMillis() - 60000
        val newTimestamp = System.currentTimeMillis()

        val original = EnrollmentState(
            isEnrolled = true,
            deviceId = "device-123",
            lastSync = oldTimestamp
        )

        val updated = original.copy(lastSync = newTimestamp)

        assertThat(updated.lastSync).isGreaterThan(original.lastSync!!)
    }
}
