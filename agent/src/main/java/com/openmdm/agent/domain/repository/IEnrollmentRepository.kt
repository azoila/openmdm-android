package com.openmdm.agent.domain.repository

import com.openmdm.agent.data.EnrollmentState
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for enrollment-related operations.
 *
 * This abstraction enables:
 * - Easy mocking in tests
 * - Separation of concerns between domain and data layers
 * - Potential for multiple implementations (e.g., mock, real)
 */
interface IEnrollmentRepository {

    /**
     * Flow of the current enrollment state.
     * Emits whenever enrollment state changes.
     */
    val enrollmentState: Flow<EnrollmentState>

    /**
     * Get the current enrollment state synchronously.
     */
    suspend fun getEnrollmentState(): EnrollmentState

    /**
     * Save enrollment data after successful device enrollment.
     */
    suspend fun saveEnrollment(
        deviceId: String,
        enrollmentId: String,
        token: String,
        refreshToken: String?,
        serverUrl: String,
        policyVersion: String?
    )

    /**
     * Update the authentication token.
     */
    suspend fun updateToken(token: String, refreshToken: String? = null)

    /**
     * Update the last sync timestamp.
     */
    suspend fun updateLastSync()

    /**
     * Update the policy version.
     */
    suspend fun updatePolicyVersion(version: String)

    /**
     * Clear all enrollment data (unenroll device).
     */
    suspend fun clearEnrollment()
}
