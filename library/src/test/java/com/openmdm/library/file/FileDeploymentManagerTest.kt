package com.openmdm.library.file

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [FileDeploymentManager].
 *
 * Tests path resolution, file deployment with progress tracking,
 * checksum verification, and batch deployment operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FileDeploymentManagerTest {

    private lateinit var context: Context
    private lateinit var fileManager: FileDeploymentManager
    private lateinit var mockServer: MockWebServer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fileManager = FileDeploymentManager.create(context)
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun teardown() {
        mockServer.shutdown()
    }

    // ============================================
    // Path Resolution Tests
    // ============================================

    @Test
    fun `resolveTargetPath resolves internal prefix to filesDir`() {
        val path = "internal://config/settings.json"

        val resolved = fileManager.resolveTargetPath(path)

        assertThat(resolved.path).startsWith(context.filesDir.path)
        assertThat(resolved.path).endsWith("config/settings.json")
    }

    @Test
    fun `resolveTargetPath resolves cache prefix to cacheDir`() {
        val path = "cache://temp/data.tmp"

        val resolved = fileManager.resolveTargetPath(path)

        assertThat(resolved.path).startsWith(context.cacheDir.path)
        assertThat(resolved.path).endsWith("temp/data.tmp")
    }

    @Test
    fun `resolveTargetPath resolves external prefix to externalFilesDir`() {
        val path = "external://documents/report.pdf"

        val resolved = fileManager.resolveTargetPath(path)

        assertThat(resolved.path).contains("documents/report.pdf")
    }

    @Test
    fun `resolveTargetPath resolves downloads prefix`() {
        mockkStatic(Environment::class)
        val downloadsDir = File(context.cacheDir, "Downloads")
        downloadsDir.mkdirs()
        every { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } returns downloadsDir

        val path = "downloads://file.zip"

        val resolved = fileManager.resolveTargetPath(path)

        assertThat(resolved.name).isEqualTo("file.zip")
        unmockkStatic(Environment::class)
    }

    @Test
    fun `resolveTargetPath resolves absolute path as-is`() {
        val path = "/data/local/tmp/file.txt"

        val resolved = fileManager.resolveTargetPath(path)

        assertThat(resolved.path).isEqualTo("/data/local/tmp/file.txt")
    }

    @Test
    fun `resolveTargetPath defaults to internal for relative paths`() {
        val path = "some/relative/path.txt"

        val resolved = fileManager.resolveTargetPath(path)

        assertThat(resolved.path).startsWith(context.filesDir.path)
        assertThat(resolved.path).endsWith("some/relative/path.txt")
    }

    // ============================================
    // File Deployment Tests
    // ============================================

    @Test
    fun `deployFileSync downloads file successfully`() = runTest {
        val content = "test file content"
        mockServer.enqueue(MockResponse().setBody(content))

        val deployment = FileDeployment(
            url = mockServer.url("/test.txt").toString(),
            path = "internal://test/downloaded.txt"
        )

        val result = fileManager.deployFileSync(deployment)

        assertThat(result.isSuccess).isTrue()
        val file = result.getOrThrow()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).isEqualTo(content)

        // Cleanup
        file.delete()
    }

    @Test
    fun `deployFileSync creates parent directories`() = runTest {
        val content = "nested content"
        mockServer.enqueue(MockResponse().setBody(content))

        val deployment = FileDeployment(
            url = mockServer.url("/nested.txt").toString(),
            path = "internal://deeply/nested/path/file.txt"
        )

        val result = fileManager.deployFileSync(deployment)

        assertThat(result.isSuccess).isTrue()
        val file = result.getOrThrow()
        assertThat(file.parentFile?.exists()).isTrue()

        // Cleanup
        file.delete()
    }

    @Test
    fun `deployFileSync verifies checksum when provided`() = runTest {
        val content = "checksum test"
        // SHA-256 of "checksum test"
        val correctHash = "50743bc89b03b938f412094255c8e3cf1658b470dbc01d7db80a11dc39adfb9a"
        mockServer.enqueue(MockResponse().setBody(content))

        val deployment = FileDeployment(
            url = mockServer.url("/checksum.txt").toString(),
            path = "internal://checksum/file.txt",
            hash = correctHash
        )

        val result = fileManager.deployFileSync(deployment)

        assertThat(result.isSuccess).isTrue()

        // Cleanup
        result.getOrNull()?.delete()
    }

    @Test
    fun `deployFileSync fails on checksum mismatch`() = runTest {
        val content = "checksum test"
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"
        mockServer.enqueue(MockResponse().setBody(content))

        val deployment = FileDeployment(
            url = mockServer.url("/checksum.txt").toString(),
            path = "internal://checksum/file.txt",
            hash = wrongHash
        )

        val result = fileManager.deployFileSync(deployment)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Checksum mismatch")
    }

    @Test
    fun `deployFileSync fails on HTTP error`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(404))

        val deployment = FileDeployment(
            url = mockServer.url("/notfound.txt").toString(),
            path = "internal://error/file.txt"
        )

        val result = fileManager.deployFileSync(deployment)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("404")
    }

    @Test
    fun `deployFileSync skips when overwrite is false and file exists`() = runTest {
        // Create existing file
        val existingFile = fileManager.resolveTargetPath("internal://existing/file.txt")
        existingFile.parentFile?.mkdirs()
        existingFile.writeText("existing content")

        mockServer.enqueue(MockResponse().setBody("new content"))

        val deployment = FileDeployment(
            url = mockServer.url("/new.txt").toString(),
            path = "internal://existing/file.txt",
            overwrite = false
        )

        val result = fileManager.deployFileSync(deployment)

        assertThat(result.isSuccess).isTrue()
        // Content should remain unchanged
        assertThat(existingFile.readText()).isEqualTo("existing content")

        // Cleanup
        existingFile.delete()
    }

    // ============================================
    // Progress Tracking Tests
    // ============================================

    @Test
    fun `deployFile emits progress updates`() = runTest {
        val content = "progress test content"
        mockServer.enqueue(MockResponse()
            .setBody(content)
            .setHeader("Content-Length", content.length.toString()))

        val deployment = FileDeployment(
            url = mockServer.url("/progress.txt").toString(),
            path = "internal://progress/file.txt"
        )

        val progressUpdates = fileManager.deployFile(deployment).toList()

        // Should have Starting, at least one Downloading, Verifying, and Completed
        assertThat(progressUpdates.first()).isInstanceOf(DeploymentProgress.Starting::class.java)
        assertThat(progressUpdates.last()).isInstanceOf(DeploymentProgress.Completed::class.java)

        val downloadingUpdates = progressUpdates.filterIsInstance<DeploymentProgress.Downloading>()
        assertThat(downloadingUpdates).isNotEmpty()

        // Cleanup
        (progressUpdates.last() as DeploymentProgress.Completed).file.delete()
    }

    @Test
    fun `deployFile emits Failed on error`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val deployment = FileDeployment(
            url = mockServer.url("/error.txt").toString(),
            path = "internal://error/file.txt"
        )

        val progressUpdates = fileManager.deployFile(deployment).toList()

        assertThat(progressUpdates.last()).isInstanceOf(DeploymentProgress.Failed::class.java)
        val failed = progressUpdates.last() as DeploymentProgress.Failed
        assertThat(failed.error).contains("500")
    }

    // ============================================
    // Batch Deployment Tests
    // ============================================

    @Test
    fun `deployFiles handles multiple deployments`() = runTest {
        mockServer.enqueue(MockResponse().setBody("file1 content"))
        mockServer.enqueue(MockResponse().setBody("file2 content"))
        mockServer.enqueue(MockResponse().setResponseCode(404)) // Third will fail

        val deployments = listOf(
            FileDeployment(mockServer.url("/file1.txt").toString(), "internal://batch/file1.txt"),
            FileDeployment(mockServer.url("/file2.txt").toString(), "internal://batch/file2.txt"),
            FileDeployment(mockServer.url("/file3.txt").toString(), "internal://batch/file3.txt")
        )

        val result = fileManager.deployFiles(deployments)

        assertThat(result.total).isEqualTo(3)
        assertThat(result.successful).isEqualTo(2)
        assertThat(result.failed).isEqualTo(1)

        // Verify individual results
        assertThat(result.results[0].success).isTrue()
        assertThat(result.results[1].success).isTrue()
        assertThat(result.results[2].success).isFalse()

        // Cleanup
        result.results.filter { it.success }.forEach { it.file?.delete() }
    }

    @Test
    fun `deployFilesWithProgress emits batch progress`() = runTest {
        mockServer.enqueue(MockResponse().setBody("content1"))
        mockServer.enqueue(MockResponse().setBody("content2"))

        val deployments = listOf(
            FileDeployment(mockServer.url("/batch1.txt").toString(), "internal://batchprogress/file1.txt"),
            FileDeployment(mockServer.url("/batch2.txt").toString(), "internal://batchprogress/file2.txt")
        )

        val progressUpdates = fileManager.deployFilesWithProgress(deployments).toList()

        // Should have Starting, FileStarting, FileCompleted (or FileFailed), Progress, and Completed
        assertThat(progressUpdates.first()).isInstanceOf(BatchProgress.Starting::class.java)
        assertThat(progressUpdates.last()).isInstanceOf(BatchProgress.Completed::class.java)

        val completed = progressUpdates.last() as BatchProgress.Completed
        assertThat(completed.successful).isEqualTo(2)
        assertThat(completed.failed).isEqualTo(0)
    }

    // ============================================
    // File Management Tests
    // ============================================

    @Test
    fun `deleteFile removes existing file`() {
        val file = fileManager.resolveTargetPath("internal://todelete/file.txt")
        file.parentFile?.mkdirs()
        file.writeText("to be deleted")

        val result = fileManager.deleteFile("internal://todelete/file.txt")

        assertThat(result.isSuccess).isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `deleteFile succeeds for non-existent file`() {
        val result = fileManager.deleteFile("internal://nonexistent/file.txt")

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `fileExists returns true for existing file`() {
        val file = fileManager.resolveTargetPath("internal://exists/file.txt")
        file.parentFile?.mkdirs()
        file.writeText("exists")

        val exists = fileManager.fileExists("internal://exists/file.txt")

        assertThat(exists).isTrue()

        // Cleanup
        file.delete()
    }

    @Test
    fun `fileExists returns false for non-existent file`() {
        val exists = fileManager.fileExists("internal://doesnotexist/file.txt")

        assertThat(exists).isFalse()
    }

    @Test
    fun `getFileInfo returns file information`() {
        val file = fileManager.resolveTargetPath("internal://info/file.txt")
        file.parentFile?.mkdirs()
        file.writeText("file info content")

        val info = fileManager.getFileInfo("internal://info/file.txt")

        assertThat(info).isNotNull()
        assertThat(info?.path).isEqualTo(file.absolutePath)
        assertThat(info?.size).isEqualTo("file info content".length.toLong())
        assertThat(info?.isDirectory).isFalse()
        assertThat(info?.lastModified).isGreaterThan(0)

        // Cleanup
        file.delete()
    }

    @Test
    fun `getFileInfo returns null for non-existent file`() {
        val info = fileManager.getFileInfo("internal://noinfo/file.txt")

        assertThat(info).isNull()
    }

    // ============================================
    // Edge Cases
    // ============================================

    @Test
    fun `deployFileSync handles empty file`() = runTest {
        mockServer.enqueue(MockResponse().setBody(""))

        val deployment = FileDeployment(
            url = mockServer.url("/empty.txt").toString(),
            path = "internal://empty/file.txt"
        )

        val result = fileManager.deployFileSync(deployment)

        assertThat(result.isSuccess).isTrue()
        val file = result.getOrThrow()
        assertThat(file.length()).isEqualTo(0)

        // Cleanup
        file.delete()
    }

    @Test
    fun `deployFileSync handles special characters in path`() = runTest {
        mockServer.enqueue(MockResponse().setBody("special"))

        val deployment = FileDeployment(
            url = mockServer.url("/special.txt").toString(),
            path = "internal://path with spaces/file-name_v1.2.txt"
        )

        val result = fileManager.deployFileSync(deployment)

        assertThat(result.isSuccess).isTrue()

        // Cleanup
        result.getOrNull()?.delete()
    }

    @Test
    fun `resolveTargetPath handles nested directories`() {
        val path = "internal://a/b/c/d/e/f/deep.txt"

        val resolved = fileManager.resolveTargetPath(path)

        assertThat(resolved.path).endsWith("a/b/c/d/e/f/deep.txt")
    }
}
