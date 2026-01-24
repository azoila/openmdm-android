package com.openmdm.agent.util

import com.openmdm.agent.BuildConfig
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates HMAC signatures for device enrollment
 */
@Singleton
class SignatureGenerator @Inject constructor() {

    private val secret: String = BuildConfig.DEVICE_SECRET

    /**
     * Generate enrollment signature
     *
     * The signature is an HMAC-SHA256 of: "{identifier}:{timestamp}"
     * where identifier is the first available of: macAddress, serialNumber, imei, or androidId
     *
     * This matches the OpenMDM server's verifyEnrollmentSignature function.
     */
    fun generateEnrollmentSignature(
        model: String,
        manufacturer: String,
        osVersion: String,
        serialNumber: String?,
        imei: String?,
        macAddress: String?,
        androidId: String?,
        method: String,
        timestamp: String
    ): String {
        // Use the same identifier priority as the server:
        // macAddress || serialNumber || imei || androidId
        val identifier = macAddress?.takeIf { it.isNotEmpty() }
            ?: serialNumber?.takeIf { it.isNotEmpty() }
            ?: imei?.takeIf { it.isNotEmpty() }
            ?: androidId?.takeIf { it.isNotEmpty() }
            ?: ""

        val message = "$identifier:$timestamp"
        return hmacSha256(message, secret)
    }

    private fun hmacSha256(message: String, secret: String): String {
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return signature.joinToString("") { "%02x".format(it) }
    }
}
