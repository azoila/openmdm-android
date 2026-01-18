package com.openmdm.library.file

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * File Deployment Manager
 *
 * Downloads and deploys files from remote URLs with:
 * - Progress tracking via Kotlin Flow
 * - SHA-256 checksum verification
 * - Support for special path prefixes (internal://, external://, cache://)
 * - Batch deployment
 *
 * Usage:
 * ```kotlin
 * val fileManager = FileDeploymentManager.create(context)
 *
 * // Deploy with progress tracking
 * fileManager.deployFile(FileDeployment(
 *     url = "https://example.com/config.json",
 *     path = "internal://config/settings.json",
 *     hash = "abc123..."
 * )).collect { progress ->
 *     when (progress) {
 *         is DeploymentProgress.Downloading -> println("${progress.percent}%")
 *         is DeploymentProgress.Completed -> println("Done: ${progress.file.path}")
 *         is DeploymentProgress.Failed -> println("Error: ${progress.error}")
 *     }
 * }
 *
 * // Deploy synchronously
 * val result = fileManager.deployFileSync(deployment)
 * ```
 */
class FileDeploymentManager private constructor(
    private val context: Context
) {
    companion object {
        // Path prefixes
        const val PREFIX_INTERNAL = "internal://"
        const val PREFIX_EXTERNAL = "external://"
        const val PREFIX_CACHE = "cache://"
        const val PREFIX_DOWNLOADS = "downloads://"
        const val PREFIX_DOCUMENTS = "documents://"

        // Buffer size for downloads
        private const val BUFFER_SIZE = 8192

        // Connection settings
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000

        /**
         * Create FileDeploymentManager
         */
        fun create(context: Context): FileDeploymentManager {
            return FileDeploymentManager(context.applicationContext)
        }
    }

    // ============================================
    // Single File Deployment
    // ============================================

    /**
     * Deploy a file with progress tracking.
     *
     * Returns a Flow that emits progress updates during download
     * and completes with success or failure.
     */
    fun deployFile(deployment: FileDeployment): Flow<DeploymentProgress> = flow {
        emit(DeploymentProgress.Starting(deployment))

        try {
            val targetFile = resolveTargetPath(deployment.path)
            val tempFile = File(context.cacheDir, "deploy_${System.currentTimeMillis()}.tmp")

            try {
                // Download to temp file with progress
                downloadWithProgress(deployment.url, tempFile) { bytesDownloaded, totalBytes ->
                    val percent = if (totalBytes > 0) {
                        ((bytesDownloaded.toFloat() / totalBytes) * 100).toInt()
                    } else {
                        -1 // Unknown total size
                    }
                    emit(DeploymentProgress.Downloading(deployment, percent, bytesDownloaded, totalBytes))
                }

                emit(DeploymentProgress.Verifying(deployment))

                // Verify checksum if provided
                if (deployment.hash != null) {
                    val actualHash = calculateSha256(tempFile)
                    if (!actualHash.equals(deployment.hash, ignoreCase = true)) {
                        throw IOException("Checksum mismatch: expected ${deployment.hash}, got $actualHash")
                    }
                }

                // Ensure parent directory exists
                targetFile.parentFile?.mkdirs()

                // Move/copy to final location
                if (deployment.overwrite || !targetFile.exists()) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    emit(DeploymentProgress.Completed(deployment, targetFile))
                } else {
                    emit(DeploymentProgress.Skipped(deployment, "File exists and overwrite=false"))
                }

            } finally {
                // Clean up temp file
                tempFile.delete()
            }

        } catch (e: Exception) {
            emit(DeploymentProgress.Failed(deployment, e.message ?: "Unknown error", e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Deploy a file synchronously.
     *
     * @return Result containing the deployed File or an error
     */
    suspend fun deployFileSync(deployment: FileDeployment): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val targetFile = resolveTargetPath(deployment.path)
            val tempFile = File(context.cacheDir, "deploy_${System.currentTimeMillis()}.tmp")

            try {
                // Download to temp file
                downloadFile(deployment.url, tempFile)

                // Verify checksum if provided
                if (deployment.hash != null) {
                    val actualHash = calculateSha256(tempFile)
                    if (!actualHash.equals(deployment.hash, ignoreCase = true)) {
                        throw IOException("Checksum mismatch: expected ${deployment.hash}, got $actualHash")
                    }
                }

                // Ensure parent directory exists
                targetFile.parentFile?.mkdirs()

                // Move/copy to final location
                if (deployment.overwrite || !targetFile.exists()) {
                    tempFile.copyTo(targetFile, overwrite = true)
                }

                targetFile
            } finally {
                tempFile.delete()
            }
        }
    }

    // ============================================
    // Batch Deployment
    // ============================================

    /**
     * Deploy multiple files.
     *
     * @return BatchDeploymentResult with success/failure counts and details
     */
    suspend fun deployFiles(deployments: List<FileDeployment>): BatchDeploymentResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<SingleDeploymentResult>()

        for (deployment in deployments) {
            val result = deployFileSync(deployment)
            results.add(
                SingleDeploymentResult(
                    deployment = deployment,
                    success = result.isSuccess,
                    file = result.getOrNull(),
                    error = result.exceptionOrNull()?.message
                )
            )
        }

        BatchDeploymentResult(
            total = deployments.size,
            successful = results.count { it.success },
            failed = results.count { !it.success },
            results = results
        )
    }

    /**
     * Deploy multiple files with progress tracking.
     *
     * Emits progress for each file in sequence.
     */
    fun deployFilesWithProgress(deployments: List<FileDeployment>): Flow<BatchProgress> = flow {
        emit(BatchProgress.Starting(deployments.size))

        var completedCount = 0
        var successCount = 0
        var failedCount = 0

        for ((index, deployment) in deployments.withIndex()) {
            emit(BatchProgress.FileStarting(index, deployment))

            val result = deployFileSync(deployment)

            completedCount++
            if (result.isSuccess) {
                successCount++
                emit(BatchProgress.FileCompleted(index, deployment, result.getOrNull()!!))
            } else {
                failedCount++
                emit(BatchProgress.FileFailed(index, deployment, result.exceptionOrNull()?.message ?: "Unknown error"))
            }

            emit(BatchProgress.Progress(completedCount, deployments.size, successCount, failedCount))
        }

        emit(BatchProgress.Completed(successCount, failedCount))
    }.flowOn(Dispatchers.IO)

    // ============================================
    // Path Resolution
    // ============================================

    /**
     * Resolve a path with prefix to an actual File.
     *
     * Supported prefixes:
     * - internal:// -> context.filesDir
     * - external:// -> external files dir
     * - cache:// -> context.cacheDir
     * - downloads:// -> Downloads directory
     * - documents:// -> Documents directory
     * - /absolute/path -> used as-is
     */
    fun resolveTargetPath(path: String): File {
        return when {
            path.startsWith(PREFIX_INTERNAL) -> {
                File(context.filesDir, path.removePrefix(PREFIX_INTERNAL))
            }
            path.startsWith(PREFIX_EXTERNAL) -> {
                val externalDir = context.getExternalFilesDir(null)
                    ?: throw IOException("External storage not available")
                File(externalDir, path.removePrefix(PREFIX_EXTERNAL))
            }
            path.startsWith(PREFIX_CACHE) -> {
                File(context.cacheDir, path.removePrefix(PREFIX_CACHE))
            }
            path.startsWith(PREFIX_DOWNLOADS) -> {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(downloadsDir, path.removePrefix(PREFIX_DOWNLOADS))
            }
            path.startsWith(PREFIX_DOCUMENTS) -> {
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                File(documentsDir, path.removePrefix(PREFIX_DOCUMENTS))
            }
            path.startsWith("/") -> {
                // Absolute path
                File(path)
            }
            else -> {
                // Default to internal storage
                File(context.filesDir, path)
            }
        }
    }

    // ============================================
    // Download Helpers
    // ============================================

    /**
     * Download a file without progress tracking
     */
    private fun downloadFile(url: String, targetFile: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.requestMethod = "GET"

        try {
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: ${connection.responseCode} ${connection.responseMessage}")
            }

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Download a file with progress callback
     */
    private suspend fun downloadWithProgress(
        url: String,
        targetFile: File,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.requestMethod = "GET"

        try {
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: ${connection.responseCode} ${connection.responseMessage}")
            }

            val totalBytes = connection.contentLengthLong

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var bytesDownloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        onProgress(bytesDownloaded, totalBytes)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // ============================================
    // Checksum Verification
    // ============================================

    /**
     * Calculate SHA-256 hash of a file
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)

        file.inputStream().use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ============================================
    // File Management
    // ============================================

    /**
     * Delete a deployed file
     */
    fun deleteFile(path: String): Result<Unit> = runCatching {
        val file = resolveTargetPath(path)
        if (file.exists()) {
            if (!file.delete()) {
                throw IOException("Failed to delete file: $path")
            }
        }
    }

    /**
     * Check if a file exists
     */
    fun fileExists(path: String): Boolean {
        return resolveTargetPath(path).exists()
    }

    /**
     * Get file info
     */
    fun getFileInfo(path: String): FileInfo? {
        val file = resolveTargetPath(path)
        if (!file.exists()) return null

        return FileInfo(
            path = file.absolutePath,
            size = file.length(),
            lastModified = file.lastModified(),
            isDirectory = file.isDirectory
        )
    }
}

// ============================================
// Data Classes
// ============================================

/**
 * File deployment request
 */
data class FileDeployment(
    val url: String,
    val path: String,
    val hash: String? = null,
    val overwrite: Boolean = true
)

/**
 * Progress updates for single file deployment
 */
sealed class DeploymentProgress {
    abstract val deployment: FileDeployment

    data class Starting(override val deployment: FileDeployment) : DeploymentProgress()

    data class Downloading(
        override val deployment: FileDeployment,
        val percent: Int, // -1 if unknown
        val bytesDownloaded: Long,
        val totalBytes: Long // -1 if unknown
    ) : DeploymentProgress()

    data class Verifying(override val deployment: FileDeployment) : DeploymentProgress()

    data class Completed(
        override val deployment: FileDeployment,
        val file: File
    ) : DeploymentProgress()

    data class Skipped(
        override val deployment: FileDeployment,
        val reason: String
    ) : DeploymentProgress()

    data class Failed(
        override val deployment: FileDeployment,
        val error: String,
        val exception: Exception? = null
    ) : DeploymentProgress()
}

/**
 * Progress updates for batch deployment
 */
sealed class BatchProgress {
    data class Starting(val totalFiles: Int) : BatchProgress()
    data class FileStarting(val index: Int, val deployment: FileDeployment) : BatchProgress()
    data class FileCompleted(val index: Int, val deployment: FileDeployment, val file: File) : BatchProgress()
    data class FileFailed(val index: Int, val deployment: FileDeployment, val error: String) : BatchProgress()
    data class Progress(val completed: Int, val total: Int, val successful: Int, val failed: Int) : BatchProgress()
    data class Completed(val successful: Int, val failed: Int) : BatchProgress()
}

/**
 * Result of batch deployment
 */
data class BatchDeploymentResult(
    val total: Int,
    val successful: Int,
    val failed: Int,
    val results: List<SingleDeploymentResult>
)

/**
 * Result of single deployment within a batch
 */
data class SingleDeploymentResult(
    val deployment: FileDeployment,
    val success: Boolean,
    val file: File?,
    val error: String?
)

/**
 * File information
 */
data class FileInfo(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)
