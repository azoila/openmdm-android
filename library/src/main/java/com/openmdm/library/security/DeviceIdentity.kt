package com.openmdm.library.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import com.openmdm.library.telemetry.MdmTelemetryHolder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Device identity keypair lifecycle for Phase 2b enrollment.
 *
 * The agent generates an **ECDSA P-256 keypair** inside the Android
 * Keystore on first enrollment. The public key is submitted to the
 * openmdm server and pinned on the device row; all subsequent
 * re-enrollments must present a signature that verifies against the
 * pinned key. The private key **never leaves the Keystore** — all
 * signing happens inside the secure container.
 *
 * ## Why ECDSA P-256 (not RSA, not Ed25519)
 *
 * - P-256 is supported by every Android Keymaster since API 23, so
 *   the minSdk 26 floor gives us universal availability.
 * - Keys are smaller and faster to generate than RSA on the cheap
 *   kiosk hardware the agent actually runs on.
 * - Ed25519 is not supported by the Android Keystore as of API 35.
 * - `node:crypto` on the server side verifies P-256 DER signatures
 *   natively via `crypto.verify('sha256', ...)`, keeping the server
 *   dependency-free.
 *
 * ## Hardware tiers (in order of preference)
 *
 * 1. **StrongBox** (Keymaster in a separate secure element, API 28+).
 *    Requested via [KeyGenParameterSpec.Builder.setIsStrongBoxBacked]
 *    when the device advertises
 *    [PackageManager.FEATURE_STRONGBOX_KEYSTORE]. StrongBox raises
 *    the bar against physical attacks — an attacker with a dev-kit
 *    fault-injection rig against the main SoC still can't extract
 *    the key. Strict StrongBox-only enrollment would reject
 *    ~everything below flagship Pixels/Samsungs; we use it when
 *    available but never require it.
 *
 * 2. **TEE-backed Keymaster** (Trusted Execution Environment, e.g.
 *    TrustZone/TEEGRIS). Standard on every modern Android and the
 *    realistic default. Key material is protected from the main OS;
 *    an attacker has to break the TEE to extract it.
 *
 * 3. **Software Keystore**. The key lives in an app-sandboxed
 *    software-backed keystore. No hardware isolation, but still
 *    sandboxed per-app, so another app on the same device cannot
 *    access the private key without elevating to root. This is the
 *    fallback for devices without a hardware Keymaster — which
 *    includes the ZK-R32D kiosk boards that are the original driver
 *    for this work. A software-backed key is still meaningfully
 *    better than a shared HMAC secret because each device has a
 *    different key, so an APK extraction no longer compromises the
 *    whole fleet.
 *
 * Operators who need to reject software-backed keys can check
 * [KeyInfo.securityLevel] and refuse to enroll a device whose key
 * is not `SECURITY_LEVEL_TRUSTED_ENVIRONMENT` or higher.
 *
 * ## Key lifetime
 *
 * The key alias persists across app starts and across app updates
 * (upgrades preserve the Keystore). It is destroyed by:
 *
 * - A factory reset.
 * - An explicit `pm clear com.openmdm.agent`.
 * - The user or admin deleting the key via
 *   `KeyStore.deleteEntry(ALIAS)`.
 * - OS storage-pressure eviction of the entire app sandbox.
 *
 * All of the above produce the "full wipe" scenario. On recovery,
 * the agent generates a new keypair and attempts to re-enroll. The
 * openmdm server will reject that re-enrollment with
 * `PublicKeyMismatchError` if the device already has a pinned key —
 * at which point an admin must explicitly unpin the device before
 * it can rebind.
 */
interface DeviceIdentity {

    /**
     * Ensure a Keystore keypair exists for this device, generating
     * one if absent. Idempotent. Returns a snapshot of the key's
     * metadata so callers can inspect it without reaching back into
     * the Keystore.
     *
     * Throws [DeviceIdentityException] on any failure — generation
     * refusal, hardware unavailable, Keystore corruption, etc. Every
     * caller must handle this; a failure to initialize identity
     * means the agent cannot use the pinned-key enrollment path
     * and should fall back to the HMAC path (or fail, if the
     * operator has mandated pinned-key enrollment).
     */
    fun ensureKeyPair(): KeyPairInfo

    /**
     * Base64-encoded SPKI public key. This is what gets submitted
     * in the `publicKey` field of the enrollment request.
     *
     * Throws [DeviceIdentityException] if no keypair has been
     * created yet — call [ensureKeyPair] first.
     */
    fun publicKeySpkiBase64(): String

    /**
     * Sign [message] with the device identity private key.
     * Returns a base64-encoded DER ECDSA-SHA256 signature, which
     * is the format `@openmdm/core`'s `verifyEcdsaSignature`
     * expects.
     *
     * Signing happens inside the Keystore — the private key never
     * leaves the secure container. Throws [DeviceIdentityException]
     * on any failure.
     */
    fun sign(message: String): String

    /**
     * Delete the keypair. This is destructive — subsequent
     * [ensureKeyPair] calls will generate a new key, and the agent
     * will be unable to present the old public key to the server
     * for continuity checks. Only call this in response to a
     * server-signed unenroll command or an explicit admin reset.
     */
    fun deleteKeyPair()
}

/**
 * Metadata about the generated keypair. All values are read back
 * from the Keystore at generation time, so they reflect what the
 * Keymaster actually provided — StrongBox may have been requested
 * but the device may have silently fallen back to TEE, and the
 * `securityLevel` field reflects that accurately.
 */
data class KeyPairInfo(
    /** Base64-encoded SPKI public key, ready to submit in enrollment. */
    val publicKeySpkiBase64: String,
    /**
     * `true` if the Keymaster reports a hardware-backed key
     * (TEE-backed or StrongBox). `false` only when the key lives
     * in a software Keystore — usually only on devices without a
     * hardware Keymaster at all.
     */
    val isHardwareBacked: Boolean,
    /**
     * `true` when the key is StrongBox-backed. Always false on
     * API < 28 and on devices without
     * [PackageManager.FEATURE_STRONGBOX_KEYSTORE].
     */
    val isStrongBoxBacked: Boolean,
    /**
     * One of:
     * - `"software"` — software keystore, no hardware isolation.
     * - `"trusted_environment"` — TEE-backed.
     * - `"strongbox"` — separate secure element.
     * - `"unknown"` — the Keymaster did not report a security
     *    level (rare, happens on some older devices).
     */
    val securityLevel: String,
)

/** Raised on any device identity failure. */
class DeviceIdentityException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Real Android Keystore implementation. This is the production
 * path; tests should use a fake implementation that wraps Java
 * crypto directly so they don't depend on Robolectric's
 * AndroidKeyStore shadow (which is incomplete).
 */
class AndroidKeystoreDeviceIdentity(
    private val context: Context,
    private val alias: String = DEFAULT_ALIAS,
) : DeviceIdentity {

    override fun ensureKeyPair(): KeyPairInfo {
        val keystore = loadKeystore()

        if (keystore.containsAlias(alias)) {
            return describe(keystore)
        }

        // Generate a new keypair. StrongBox is attempted when the
        // device advertises support for it; otherwise we fall
        // through to the TEE-backed default.
        val generator = try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        } catch (t: Throwable) {
            val err = DeviceIdentityException(
                "Failed to obtain EC key generator from AndroidKeyStore", t,
            )
            MdmTelemetryHolder.nonFatal(err, context = "device_identity_generator_init")
            throw err
        }

        val specBuilder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // User authentication is explicitly NOT required. This
            // is a *device* identity, not a per-user credential — it
            // has to be usable by background services (heartbeat,
            // command ack) without a user present at the screen.
            .setUserAuthenticationRequired(false)

        val strongBoxRequested = requestStrongBoxIfSupported(specBuilder)

        try {
            generator.initialize(specBuilder.build())
            generator.generateKeyPair()
        } catch (strongBoxFailure: Throwable) {
            // StrongBox generation can fail even when the feature
            // flag is advertised — e.g. the secure element is full
            // or unavailable. Retry without StrongBox before giving
            // up, so cheap devices that claim support but actually
            // can't deliver it don't block enrollment entirely.
            if (strongBoxRequested) {
                MdmTelemetryHolder.nonFatal(
                    strongBoxFailure,
                    context = "device_identity_strongbox_fallback",
                )
                val fallbackSpec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
                try {
                    generator.initialize(fallbackSpec)
                    generator.generateKeyPair()
                } catch (t: Throwable) {
                    val err = DeviceIdentityException(
                        "Keystore keypair generation failed (both StrongBox and TEE)", t,
                    )
                    MdmTelemetryHolder.nonFatal(
                        err, context = "device_identity_keygen_failed",
                    )
                    throw err
                }
            } else {
                val err = DeviceIdentityException(
                    "Keystore keypair generation failed", strongBoxFailure,
                )
                MdmTelemetryHolder.nonFatal(
                    err, context = "device_identity_keygen_failed",
                )
                throw err
            }
        }

        val info = describe(keystore)
        MdmTelemetryHolder.event(
            "device_identity_keypair_generated",
            mapOf(
                "security_level" to info.securityLevel,
                "is_hardware_backed" to info.isHardwareBacked,
                "is_strongbox_backed" to info.isStrongBoxBacked,
                "strongbox_requested" to strongBoxRequested,
            ),
        )
        return info
    }

    override fun publicKeySpkiBase64(): String {
        val keystore = loadKeystore()
        val cert = keystore.getCertificate(alias)
            ?: throw DeviceIdentityException(
                "No device identity keypair. Call ensureKeyPair() first.",
            )
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    override fun sign(message: String): String {
        val keystore = loadKeystore()
        val privateKey = keystore.getKey(alias, null) as? PrivateKey
            ?: throw DeviceIdentityException(
                "No device identity private key. Call ensureKeyPair() first.",
            )

        return try {
            val signer = Signature.getInstance("SHA256withECDSA").apply {
                initSign(privateKey)
                update(message.toByteArray(Charsets.UTF_8))
            }
            Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            val err = DeviceIdentityException("Keystore signing failed", t)
            MdmTelemetryHolder.nonFatal(err, context = "device_identity_sign_failed")
            throw err
        }
    }

    override fun deleteKeyPair() {
        try {
            val keystore = loadKeystore()
            if (keystore.containsAlias(alias)) {
                keystore.deleteEntry(alias)
                MdmTelemetryHolder.event(
                    "device_identity_keypair_deleted",
                    mapOf("caller_stack" to Throwable().stackTraceToString().take(400)),
                )
            }
        } catch (t: Throwable) {
            MdmTelemetryHolder.nonFatal(t, context = "device_identity_delete_failed")
        }
    }

    // ============================================
    // Helpers
    // ============================================

    private fun loadKeystore(): KeyStore {
        return try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (t: Throwable) {
            val err = DeviceIdentityException("AndroidKeyStore unavailable", t)
            MdmTelemetryHolder.nonFatal(err, context = "device_identity_keystore_load")
            throw err
        }
    }

    /**
     * Inspect a generated key via [KeyFactory] + [KeyInfo] to
     * determine its security level. Returns a snapshot suitable
     * for logging or passing to the enrollment flow.
     */
    private fun describe(keystore: KeyStore): KeyPairInfo {
        val cert = keystore.getCertificate(alias)
            ?: throw DeviceIdentityException("Key alias $alias missing after generation")
        val privateKey = keystore.getKey(alias, null) as? PrivateKey
            ?: throw DeviceIdentityException("Private key missing for alias $alias")

        val publicKeySpki = Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)

        // KeyFactory.getKeySpec(privateKey, KeyInfo::class.java) is
        // the documented way to read Keymaster metadata about a
        // Keystore-backed key. Wrapping in try/catch because some
        // vendor Keymasters refuse this call even for valid keys.
        val (hardwareBacked, strongBox, level) = try {
            val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(privateKey, KeyInfo::class.java)
            val levelName = describeSecurityLevel(info)
            Triple(
                // Pre-API 31 the only signal is `isInsideSecureHardware`;
                // from API 31 we can read the actual security level.
                @Suppress("DEPRECATION") info.isInsideSecureHardware,
                levelName == "strongbox",
                levelName,
            )
        } catch (t: Throwable) {
            MdmTelemetryHolder.nonFatal(t, context = "device_identity_keyinfo_read")
            Triple(false, false, "unknown")
        }

        return KeyPairInfo(
            publicKeySpkiBase64 = publicKeySpki,
            isHardwareBacked = hardwareBacked,
            isStrongBoxBacked = strongBox,
            securityLevel = level,
        )
    }

    private fun describeSecurityLevel(info: KeyInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "trusted_environment"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "trusted_environment"
                KeyProperties.SECURITY_LEVEL_UNKNOWN -> "unknown"
                else -> "unknown"
            }
        }
        // Pre-API 31 we only have the boolean. Trust it for the
        // coarse classification.
        @Suppress("DEPRECATION")
        return if (info.isInsideSecureHardware) "trusted_environment" else "software"
    }

    /**
     * Call [KeyGenParameterSpec.Builder.setIsStrongBoxBacked] when the
     * device claims to support it. Returns true iff StrongBox was
     * actually requested — the caller uses this to decide whether a
     * generation failure should fall back to TEE.
     */
    private fun requestStrongBoxIfSupported(builder: KeyGenParameterSpec.Builder): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val pm = context.packageManager
        val hasStrongBox = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (!hasStrongBox) return false
        return try {
            builder.setIsStrongBoxBacked(true)
            true
        } catch (t: Throwable) {
            MdmTelemetryHolder.nonFatal(
                t, context = "device_identity_strongbox_request_failed",
            )
            false
        }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "openmdm_device_identity"
    }
}
