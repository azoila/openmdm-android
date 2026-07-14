package com.openmdm.library.security

import java.io.File
import java.security.MessageDigest

/**
 * APK integrity checking.
 *
 * Kept separate from `DeviceManager` deliberately: hashing a file has nothing to
 * do with `DevicePolicyManager`, and burying it there meant it could only be
 * exercised on a real device — which is why the silent-install path shipped for
 * so long with no verification at all.
 */
object ApkIntegrity {

    /** Hex-encoded SHA-256, streamed so a large APK never sits in memory. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * True when [file] hashes to [expectedSha256] (case-insensitive hex).
     *
     * An install whose hash does not match must not proceed: on a Device Owner,
     * installing an APK you cannot vouch for is arbitrary code execution at the
     * highest privilege the platform offers.
     */
    fun matches(file: File, expectedSha256: String): Boolean =
        sha256(file).equals(expectedSha256, ignoreCase = true)
}
