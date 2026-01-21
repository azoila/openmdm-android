package com.openmdm.agent.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.openmdm.agent.data.MDMRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * ContentProvider that exposes MDM enrollment information to other apps.
 *
 * This allows apps like MidiaMob Player to query the MDM agent for:
 * - Device ID (enrollment ID / pairing code)
 * - Server URL
 * - Enrollment status
 *
 * URI: content://com.openmdm.agent.provider/enrollment
 *
 * Columns returned:
 * - device_id: The device code used during enrollment (pairing code)
 * - enrollment_id: Same as device_id (for compatibility)
 * - server_url: The MDM server URL
 * - is_enrolled: "true" or "false"
 *
 * Usage from client app:
 * ```kotlin
 * val uri = Uri.parse("content://com.openmdm.agent.provider/enrollment")
 * val cursor = contentResolver.query(uri, null, null, null, null)
 * cursor?.use {
 *     if (it.moveToFirst()) {
 *         val deviceId = it.getString(it.getColumnIndexOrThrow("device_id"))
 *         val serverUrl = it.getString(it.getColumnIndexOrThrow("server_url"))
 *         val isEnrolled = it.getString(it.getColumnIndexOrThrow("is_enrolled")) == "true"
 *     }
 * }
 * ```
 */
class MDMContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "MDMContentProvider"

        const val AUTHORITY = "com.openmdm.agent.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/enrollment")

        // Column names
        const val COLUMN_DEVICE_ID = "device_id"
        const val COLUMN_ENROLLMENT_ID = "enrollment_id"
        const val COLUMN_SERVER_URL = "server_url"
        const val COLUMN_IS_ENROLLED = "is_enrolled"
        const val COLUMN_IS_MANAGED = "is_managed"

        private const val ENROLLMENT = 1

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "enrollment", ENROLLMENT)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MDMContentProviderEntryPoint {
        fun mdmRepository(): MDMRepository
    }

    private lateinit var repository: MDMRepository

    override fun onCreate(): Boolean {
        Log.d(TAG, "MDMContentProvider created")
        return true
    }

    private fun getRepository(): MDMRepository {
        if (!::repository.isInitialized) {
            val appContext = context?.applicationContext
                ?: throw IllegalStateException("Context not available")
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext,
                MDMContentProviderEntryPoint::class.java
            )
            repository = entryPoint.mdmRepository()
        }
        return repository
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            ENROLLMENT -> {
                Log.d(TAG, "Query for enrollment data from: ${callingPackage ?: "unknown"}")
                getEnrollmentCursor()
            }
            else -> {
                Log.w(TAG, "Unknown URI: $uri")
                null
            }
        }
    }

    private fun getEnrollmentCursor(): Cursor {
        val columns = arrayOf(
            COLUMN_DEVICE_ID,
            COLUMN_ENROLLMENT_ID,
            COLUMN_SERVER_URL,
            COLUMN_IS_ENROLLED,
            COLUMN_IS_MANAGED
        )

        val cursor = MatrixCursor(columns)

        try {
            // Use runBlocking since ContentProvider.query is synchronous
            val enrollmentState = runBlocking {
                getRepository().getEnrollmentState()
            }

            Log.d(TAG, "Returning enrollment state: isEnrolled=${enrollmentState.isEnrolled}, enrollmentId=${enrollmentState.enrollmentId}")

            // Add row with enrollment data
            cursor.addRow(arrayOf(
                enrollmentState.enrollmentId ?: "",  // device_id (the pairing code)
                enrollmentState.enrollmentId ?: "",  // enrollment_id (same)
                enrollmentState.serverUrl,           // server_url
                enrollmentState.isEnrolled.toString(), // is_enrolled
                enrollmentState.isEnrolled.toString()  // is_managed (same as enrolled for compatibility)
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get enrollment state", e)
            // Return empty cursor on error
            cursor.addRow(arrayOf("", "", "", "false", "false"))
        }

        return cursor
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            ENROLLMENT -> "vnd.android.cursor.item/vnd.$AUTHORITY.enrollment"
            else -> null
        }
    }

    // Read-only provider - no insert/update/delete
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
