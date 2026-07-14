package com.openmdm.library.security

/**
 * Canonical message builder for Phase 2b device-pinned-key
 * enrollment.
 *
 * This **must** stay in lockstep with `@openmdm/core`'s
 * `canonicalEnrollmentMessage` function at
 * `packages/core/src/device-identity.ts`. Any drift is a wire
 * break and silently prevents every new device from enrolling.
 *
 * ## Shape
 *
 * Eleven fields joined by `|`:
 *
 * ```
 * publicKey | model | manufacturer | osVersion |
 *   serialNumber | imei | macAddress | androidId |
 *   method | timestamp | challenge
 * ```
 *
 * - **`publicKey`** is the base64-encoded SPKI public key, placed
 *   first so the signature's intent is visible at a glance in
 *   server-side logs.
 * - **Optional identifier fields** (serialNumber, imei, macAddress,
 *   androidId) are rendered as **empty strings**, not omitted, so
 *   the field count stays at 11. A device that only has an
 *   androidId still produces an 11-field message with three empty
 *   slots.
 * - **`challenge`** is the nonce issued by
 *   `GET /agent/enroll/challenge` and must be echoed back in the
 *   request body so the server can atomically consume it during
 *   verification. This is the replay protection.
 *
 * ## Why this is a separate file
 *
 * Keeping the canonical builder as a pure function (no
 * [android.util.Base64], no Keystore, no context) means it can be
 * unit tested on plain JVM without Robolectric. The
 * [com.openmdm.agent.security.CanonicalEnrollmentMessageTest]
 * pins the shape so any future refactor that accidentally adds,
 * removes, or reorders a field fails the test rather than the
 * production enrollment flow.
 */
object CanonicalEnrollmentMessage {

    /**
     * Build the canonical message that will be ECDSA-signed and
     * submitted as the `signature` field of the enrollment
     * request.
     */
    @Suppress("LongParameterList")
    fun build(
        publicKey: String,
        model: String,
        manufacturer: String,
        osVersion: String,
        serialNumber: String?,
        imei: String?,
        macAddress: String?,
        androidId: String?,
        method: String,
        timestamp: String,
        challenge: String,
    ): String = listOf(
        publicKey,
        model,
        manufacturer,
        osVersion,
        serialNumber.orEmpty(),
        imei.orEmpty(),
        macAddress.orEmpty(),
        androidId.orEmpty(),
        method,
        timestamp,
        challenge,
    ).joinToString(separator = "|")
}
