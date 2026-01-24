package com.openmdm.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting MDM commands.
 *
 * Commands are stored locally to ensure they survive app restarts,
 * process death, and network failures. WorkManager workers process
 * pending commands with retry logic.
 */
@Entity(tableName = "pending_commands")
data class CommandEntity(
    @PrimaryKey
    val id: String,

    /** Command type (e.g., "installApp", "uninstallApp", "setPolicy") */
    val type: String,

    /** JSON-serialized command payload */
    val payloadJson: String?,

    /** Command status: PENDING, IN_PROGRESS, COMPLETED, FAILED */
    val status: String,

    /** Timestamp when command was received */
    val createdAt: Long,

    /** Number of execution attempts */
    val attemptCount: Int = 0,

    /** Timestamp of last execution attempt */
    val lastAttemptAt: Long? = null,

    /** Error message from last failed attempt */
    val errorMessage: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
    }
}
