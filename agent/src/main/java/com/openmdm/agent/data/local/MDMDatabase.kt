package com.openmdm.agent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.openmdm.agent.data.local.dao.CommandDao
import com.openmdm.agent.data.local.entity.CommandEntity

/**
 * Room database for MDM agent local storage.
 *
 * Stores:
 * - Pending commands that need to be executed with retry support
 *
 * This database ensures command persistence across app restarts
 * and process death, enabling reliable command execution.
 */
@Database(
    entities = [CommandEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MDMDatabase : RoomDatabase() {

    /**
     * DAO for command operations.
     */
    abstract fun commandDao(): CommandDao

    companion object {
        const val DATABASE_NAME = "mdm_database"
    }
}
