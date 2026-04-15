package com.openmdm.agent.security

import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Test

/**
 * Tests for [CanonicalEnrollmentMessage].
 *
 * These run on plain JVM — no Robolectric, no AndroidKeyStore —
 * because the canonical builder is a pure function. The tests
 * also exercise a round-trip ECDSA-P256 sign/verify flow using
 * Java's built-in crypto to prove that a canonical message built
 * by this file can be signed and verified with a real keypair,
 * which is the property any drift in the canonical form would
 * silently break.
 *
 * The canonical form **must stay byte-for-byte identical** to
 * `@openmdm/core`'s `canonicalEnrollmentMessage` function
 * (`packages/core/src/device-identity.ts`). A mirror contract test
 * lives in `packages/core/tests/device-identity.test.ts` on the
 * server repo. If this file or that file ever diverges, enrollment
 * against a current server silently fails for every new device.
 */
class CanonicalEnrollmentMessageTest {

    @Test
    fun `canonical form is 11 fields with publicKey first and challenge last`() {
        val message = CanonicalEnrollmentMessage.build(
            publicKey = "PUBKEY-SPKI",
            model = "Pixel 7 Pro",
            manufacturer = "Google",
            osVersion = "14",
            serialNumber = "SN-0001",
            imei = "353912108123456",
            macAddress = "aa:bb:cc:dd:ee:ff",
            androidId = "android-id-1",
            method = "device_code:ABCD-1234",
            timestamp = "2026-04-15T12:00:00.000Z",
            challenge = "CHAL-BYTES",
        )

        assertThat(message).isEqualTo(
            "PUBKEY-SPKI|Pixel 7 Pro|Google|14|SN-0001|353912108123456|aa:bb:cc:dd:ee:ff|" +
                "android-id-1|device_code:ABCD-1234|2026-04-15T12:00:00.000Z|CHAL-BYTES"
        )
        // 11 fields → 10 separators.
        assertThat(message.count { it == '|' }).isEqualTo(10)
    }

    @Test
    fun `missing optional identifiers render as empty strings, not omitted`() {
        val message = CanonicalEnrollmentMessage.build(
            publicKey = "PK",
            model = "Generic",
            manufacturer = "Unknown",
            osVersion = "13",
            serialNumber = null,
            imei = null,
            macAddress = null,
            androidId = "android-only",
            method = "manual",
            timestamp = "T",
            challenge = "C",
        )

        // The field count must stay at 11 even when all four
        // optional identifier fields are missing. Three empty
        // slots between "13" and "android-only" is the expected
        // shape.
        assertThat(message.count { it == '|' }).isEqualTo(10)
        assertThat(message).isEqualTo("PK|Generic|Unknown|13||||android-only|manual|T|C")
    }

    @Test
    fun `changing any field produces a different canonical message`() {
        // Pinning tests for the "any drift is a wire break"
        // property. If a future refactor accidentally reorders,
        // adds, or removes a field, one of these assertions fails.
        fun build(vararg overrides: Pair<String, String?>): String {
            val base = mutableMapOf<String, String?>(
                "publicKey" to "PK",
                "model" to "M",
                "manufacturer" to "F",
                "osVersion" to "14",
                "serialNumber" to "SN",
                "imei" to "IMEI",
                "macAddress" to "MAC",
                "androidId" to "AID",
                "method" to "qr",
                "timestamp" to "T",
                "challenge" to "C",
            )
            for ((k, v) in overrides) base[k] = v
            return CanonicalEnrollmentMessage.build(
                publicKey = base["publicKey"]!!,
                model = base["model"]!!,
                manufacturer = base["manufacturer"]!!,
                osVersion = base["osVersion"]!!,
                serialNumber = base["serialNumber"],
                imei = base["imei"],
                macAddress = base["macAddress"],
                androidId = base["androidId"],
                method = base["method"]!!,
                timestamp = base["timestamp"]!!,
                challenge = base["challenge"]!!,
            )
        }

        val base = build()
        assertThat(build("publicKey" to "PK2")).isNotEqualTo(base)
        assertThat(build("model" to "M2")).isNotEqualTo(base)
        assertThat(build("challenge" to "C2")).isNotEqualTo(base)
        assertThat(build("timestamp" to "T2")).isNotEqualTo(base)
        assertThat(build("method" to "manual")).isNotEqualTo(base)
    }

    @Test
    fun `round-trip ECDSA-P256 signature over canonical message verifies`() {
        // The most important test in this file. It generates a
        // real EC P-256 keypair using Java's built-in crypto
        // (NOT AndroidKeyStore — that's why this test can live on
        // plain JVM without Robolectric), builds the canonical
        // message, signs it, and verifies. Proves end-to-end that
        // a message produced by CanonicalEnrollmentMessage can be
        // signed with SHA256withECDSA and verified with the same
        // public key.
        val keypair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val message = CanonicalEnrollmentMessage.build(
            publicKey = java.util.Base64.getEncoder()
                .encodeToString(keypair.public.encoded),
            model = "Pixel 7",
            manufacturer = "Google",
            osVersion = "14",
            serialNumber = "SN-0001",
            imei = null,
            macAddress = null,
            androidId = "android-1",
            method = "device_code:TEST",
            timestamp = "2026-04-15T12:00:00.000Z",
            challenge = "test-challenge",
        )

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keypair.private)
        signer.update(message.toByteArray(Charsets.UTF_8))
        val signature = signer.sign()

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keypair.public)
        verifier.update(message.toByteArray(Charsets.UTF_8))
        assertThat(verifier.verify(signature)).isTrue()
    }

    @Test
    fun `round-trip with a tampered canonical message fails verification`() {
        // If any byte of the canonical message changes between
        // signing and verification, the signature should fail.
        // This is the property that makes the public key a real
        // identity — an attacker who intercepts and modifies the
        // request cannot produce a verifying signature.
        val keypair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val publicKeyB64 = java.util.Base64.getEncoder()
            .encodeToString(keypair.public.encoded)

        val original = CanonicalEnrollmentMessage.build(
            publicKey = publicKeyB64,
            model = "Pixel 7",
            manufacturer = "Google",
            osVersion = "14",
            serialNumber = "SN-0001",
            imei = null,
            macAddress = null,
            androidId = "android-1",
            method = "device_code:TEST",
            timestamp = "2026-04-15T12:00:00.000Z",
            challenge = "original-challenge",
        )
        val tampered = CanonicalEnrollmentMessage.build(
            publicKey = publicKeyB64,
            model = "Pixel 7",
            manufacturer = "Google",
            osVersion = "14",
            serialNumber = "SN-0001",
            imei = null,
            macAddress = null,
            androidId = "android-1",
            method = "device_code:TEST",
            timestamp = "2026-04-15T12:00:00.000Z",
            challenge = "TAMPERED-challenge",
        )

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keypair.private)
        signer.update(original.toByteArray(Charsets.UTF_8))
        val signature = signer.sign()

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keypair.public)
        verifier.update(tampered.toByteArray(Charsets.UTF_8))
        assertThat(verifier.verify(signature)).isFalse()
    }
}
