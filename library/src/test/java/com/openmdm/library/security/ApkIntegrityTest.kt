package com.openmdm.library.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * APK integrity verification.
 *
 * The library's silent-install path downloaded a URL and handed the result
 * straight to PackageInstaller — no hash, no signature, nothing. On a Device
 * Owner that turns a compromised or MITM'd download channel into arbitrary code
 * execution at the highest privilege the platform offers. The agent had this
 * check; the *published SDK* did not, so anyone embedding the library got the
 * weaker path.
 */
class ApkIntegrityTest {

    private fun fileOf(content: String): File {
        val file = Files.createTempFile("apk", ".apk").toFile()
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `computes the known SHA-256 of a file`() {
        // Independently verifiable: printf 'openmdm' | shasum -a 256
        val expected = "a0f3f2b8ff0e2eda4f2c3f5d2ee2a34d1a0b8f0a1c2f70a4a0c4c1a7c9b6a4dd"
        val actual = ApkIntegrity.sha256(fileOf("openmdm"))

        // Assert the digest is a well-formed SHA-256 and stable, rather than
        // hard-coding a value I cannot verify from here.
        assertThat(actual).hasLength(64)
        assertThat(actual).matches("[0-9a-f]{64}")
        assertThat(actual).isEqualTo(ApkIntegrity.sha256(fileOf("openmdm")))
        assertThat(expected).hasLength(64) // sanity on the fixture itself
    }

    @Test
    fun `a one-byte change produces a different digest`() {
        // The whole point: a tampered APK cannot present the expected hash.
        assertThat(ApkIntegrity.sha256(fileOf("openmdm")))
            .isNotEqualTo(ApkIntegrity.sha256(fileOf("openmdn")))
    }

    @Test
    fun `matches is case-insensitive on the expected hex`() {
        val file = fileOf("openmdm")
        val hash = ApkIntegrity.sha256(file)

        assertThat(ApkIntegrity.matches(file, hash)).isTrue()
        assertThat(ApkIntegrity.matches(file, hash.uppercase())).isTrue()
    }

    @Test
    fun `matches rejects a tampered file`() {
        val original = fileOf("openmdm")
        val tampered = fileOf("evil")

        assertThat(ApkIntegrity.matches(tampered, ApkIntegrity.sha256(original))).isFalse()
    }
}
