package com.openmdm.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmdm.agent.data.local.entity.CommandEntity

/**
 * Data Access Object for MDM commands.
 *
 * Provides operations to persist, query, and update command status
 * for reliable command execution with retry support.
 */
@Dao
interface CommandDao {

    /**
     * Get all pending or in-progress commands, ordered by creation time.
     */
    @Query("""
        SELECT * FROM pending_commands
        WHERE status IN ('PENDING', 'IN_PROGRESS')
        ORDER BY createdAt ASC
    """)
    suspend fun getPendingCommands(): List<CommandEntity>

    /**
     * Get a specific command by ID.
     */
    @Query("SELECT * FROM pending_commands WHERE id = :id")
    suspend fun getCommandById(id: String): CommandEntity?

    /**
     * Insert or replace a command.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(command: CommandEntity)

    /**
     * Update command status with attempt tracking.
     */
    @Query("""
        UPDATE pending_commands
        SET status = :status,
            attemptCount = attemptCount + 1,
            lastAttemptAt = :timestamp,
            errorMessage = :error
        WHERE id = :id
    """)
    suspend fun updateCommandStatus(
        id: String,
        status: String,
        timestamp: Long,
        error: String? = null
    )

    /**
     * Mark command as in progress.
     */
    @Query("""
        UPDATE pending_commands
        SET status = 'IN_PROGRESS',
            lastAttemptAt = :timestamp
        WHERE id = :id
    """)
    suspend fun markInProgress(id: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Mark command as completed.
     */
    @Query("""
        UPDATE pending_commands
        SET status = 'COMPLETED',
            lastAttemptAt = :timestamp,
            errorMessage = NULL
        WHERE id = :id
    """)
    suspend fun markCompleted(id: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Mark command as failed with error message.
     */
    @Query("""
        UPDATE pending_commands
        SET status = 'FAILED',
            attemptCount = attemptCount + 1,
            lastAttemptAt = :timestamp,
            errorMessage = :error
        WHERE id = :id
    """)
    suspend fun markFailed(id: String, error: String?, timestamp: Long = System.currentTimeMillis())

    /**
     * Reset failed commands to pending for retry.
     * Only resets commands that haven't exceeded max attempts.
     */
    @Query("""
        UPDATE pending_commands
        SET status = 'PENDING'
        WHERE status = 'FAILED'
        AND attemptCount < :maxAttempts
    """)
    suspend fun resetFailedCommands(maxAttempts: Int = 5)

    /**
     * Delete old completed commands to prevent database bloat.
     */
    @Query("""
        DELETE FROM pending_commands
        WHERE status = 'COMPLETED'
        AND lastAttemptAt < :olderThan
    """)
    suspend fun deleteOldCompletedCommands(olderThan: Long)

    /**
     * Delete permanently failed commands (exceeded max retries).
     */
    @Query("""
        DELETE FROM pending_commands
        WHERE status = 'FAILED'
        AND attemptCount >= :maxAttempts
        AND lastAttemptAt < :olderThan
    """)
    suspend fun deleteOldFailedCommands(maxAttempts: Int, olderThan: Long)

    /**
     * Get count of pending commands.
     */
    @Query("SELECT COUNT(*) FROM pending_commands WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int
}
