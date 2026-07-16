package com.openmdm.agent.util

import com.openmdm.agent.BuildConfig
import com.openmdm.agent.data.ProvisioningStore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates HMAC signatures for device enrollment.
 *
 * The signing secret follows the same precedence as the server URL
 * (see `AppModule.provideServerUrl`): a secret delivered in the
 * provisioning admin extras (`openmdm.device_secret`) wins, and the
 * compiled-in `BuildConfig.DEVICE_SECRET` is the fallback for fleets
 * that fork and rebuild the agent. A stock APK provisioned by QR has
 * no meaningful compiled-in secret, so signing with anything but the
 * provisioned one guarantees a signature mismatch on servers that
 * take the HMAC path.
 *
 * The canonical message is a nine-field pipe-delimited string:
 *
 *   model | manufacturer | osVersion | serialNumber | imei | macAddress | androidId | method | timestamp
 *
 * Empty optional identifiers are rendered as empty strings (NOT omitted) so
 * the field count is stable. This matches the server-side
 * `verifyEnrollmentSignature` in `@openmdm/core@^0.7.0` and the contract test
 * at `packages/core/tests/enrollment-signature.test.ts` in the openmdm repo.
 *
 * **Historical note.** Prior to this fix, the canonical form was
 * `"{identifier}:{timestamp}"` where `identifier` was the first non-empty
 * of `macAddress`, `serialNumber`, `imei`, or `androidId`. That form was
 * inherited from the pre-openmdm-0.7.0 server and was silently
 * incompatible with the current openmdm server — every enrollment failed
 * with "Invalid enrollment signature" and nobody noticed because the
 * agent doesn't surface enrollment errors loudly. If you are debugging
 * enrollment against an older openmdm server (< 0.7.0), use a matching
 * older version of this library, or upgrade the server first.
 *
 * Any change to the field list, order, or separator is a wire break and
 * must land in lockstep with the server side.
 */
@Singleton
class SignatureGenerator @Inject constructor(
    private val provisioningStore: ProvisioningStore,
) {

    // Resolved at signing time, not construction time: the provisioning
    // receiver writes the store while this singleton may already exist.
    private val secret: String
        get() = provisioningStore.deviceSecret ?: BuildConfig.DEVICE_SECRET

    /**
     * Generate an HMAC-SHA256 enrollment signature over the canonical
     * nine-field message. Returns a lowercase hex string.
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
        val message = listOf(
            model,
            manufacturer,
            osVersion,
            serialNumber.orEmpty(),
            imei.orEmpty(),
            macAddress.orEmpty(),
            androidId.orEmpty(),
            method,
            timestamp
        ).joinToString(separator = "|")

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
