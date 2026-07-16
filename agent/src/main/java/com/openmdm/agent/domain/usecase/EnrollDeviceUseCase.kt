package com.openmdm.agent.domain.usecase

import android.content.Context
import com.google.gson.Gson
import com.openmdm.agent.BuildConfig
import com.openmdm.agent.data.EnrollmentRequest
import com.openmdm.agent.data.MDMApi
import com.openmdm.agent.di.ServerUrl
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.domain.repository.IEnrollmentRepository
import com.openmdm.library.security.CanonicalEnrollmentMessage
import com.openmdm.library.security.DeviceIdentity
import com.openmdm.library.security.DeviceIdentityException
import com.openmdm.agent.telemetry.AgentTelemetryHolder
import com.openmdm.agent.util.DeviceInfoCollector
import com.openmdm.agent.util.SignatureGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Use case for enrolling a device with the MDM server.
 *
 * ## Two enrollment paths
 *
 * This agent supports both the Phase 2a HMAC path (legacy, still
 * accepted by the server) and the Phase 2b device-pinned-key path
 * (preferred, rolled out gradually).
 *
 * **Pinned-key path (preferred):** the agent generates an ECDSA
 * P-256 keypair inside the Android Keystore, fetches a challenge
 * from `GET /agent/enroll/challenge`, signs the canonical eleven-
 * field message (including the public key and the challenge)
 * with its Keystore private key, and submits the public key,
 * challenge, and signature to `/agent/enroll`. The server pins the
 * public key on first enroll and verifies continuity on every
 * subsequent re-enrollment. See `docs/concepts/enrollment` on the
 * server repo for the full flow.
 *
 * **HMAC path (fallback):** the agent signs the nine-field
 * canonical form with the device secret — the provisioning-time
 * secret when one was delivered in the admin extras, otherwise
 * `BuildConfig.DEVICE_SECRET` — and submits the resulting hex
 * HMAC. No public key, no challenge.
 *
 * ## When the fallback fires
 *
 * The fallback to HMAC fires in exactly two cases:
 *
 *  1. **Keystore unavailable** — `DeviceIdentity.ensureKeyPair()`
 *     throws. This happens on devices with no hardware Keymaster
 *     and a broken software keystore (rare, but observed on some
 *     corrupted ROMs).
 *  2. **Server doesn't support challenges** — `GET /agent/enroll/
 *     challenge` returns 503 (the server's adapter does not
 *     implement challenge storage). This happens on openmdm
 *     versions before 0.9.0 or against Drizzle adapters that don't
 *     pass the `enrollmentChallenges` table.
 *
 * Any **other** failure in the pinned-key path — network error,
 * wrong secret, server-side signature rejection, etc. — does NOT
 * fall back to HMAC. A failure that is not one of the two cases
 * above indicates a real problem and should be surfaced to the
 * user, not silently downgraded to the weaker path.
 */
class EnrollDeviceUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mdmApi: MDMApi,
    private val enrollmentRepository: IEnrollmentRepository,
    private val mdmRepository: MDMRepository,
    private val deviceInfoCollector: DeviceInfoCollector,
    private val signatureGenerator: SignatureGenerator,
    private val deviceIdentity: DeviceIdentity,
    @ServerUrl private val serverUrl: String,
) {
    private val gson = Gson()

    /**
     * Enroll the device with the given device code.
     * The server URL is whatever provisioning supplied, falling back to
     * `BuildConfig.MDM_SERVER_URL` — see AppModule.provideServerUrl.
     *
     * @param deviceCode Pairing code chosen by the operator. Kept on the
     *   device as the local enrollment reference (companion apps read it
     *   for activation); the server identifies the device by its hardware
     *   identifiers and never sees the code.
     * @param method How this enrollment was initiated. Must be one of the
     *   server's `EnrollmentMethod` values (`manual`, `qr`, `nfc`,
     *   `zero-touch`, `knox`, `app-only`, `adb`) — servers configured with
     *   `enrollment.allowedMethods` reject anything else.
     * @return Result indicating success or failure with error message
     */
    suspend operator fun invoke(
        deviceCode: String,
        method: String = METHOD_MANUAL,
    ): Result<Unit> {
        return try {
            val deviceInfo = deviceInfoCollector.collectDeviceInfo()
            val timestamp = generateTimestamp()

            // Attempt the pinned-key path first. Falls back to HMAC
            // on the specific failures documented in the class header,
            // throws otherwise.
            val request = buildEnrollmentRequest(
                deviceInfo = deviceInfo,
                deviceCode = deviceCode,
                timestamp = timestamp,
                method = method,
            )

            val response = mdmApi.enroll(request)
            if (response.isSuccessful) {
                response.body()?.let { body ->
                    enrollmentRepository.saveEnrollment(
                        deviceId = body.deviceId,
                        // Use the device code entered by user (pairing code) as enrollment ID
                        // This is what MidiaMob Player needs for activation
                        enrollmentId = deviceCode,
                        token = body.token,
                        refreshToken = body.refreshToken,
                        serverUrl = serverUrl,
                        policyVersion = body.policy?.version
                    )

                    // Save policy settings for launcher filtering
                    body.policy?.settings?.let { settings ->
                        val settingsJson = gson.toJson(settings)
                        mdmRepository.savePolicySettings(settingsJson)
                    }

                    // Persist the push delivery config so the foreground
                    // service knows whether to run an in-process poll loop
                    // (polling provider) or rely on push (fcm/mqtt/websocket).
                    mdmRepository.savePushConfig(
                        provider = body.pushConfig.provider,
                        pollingIntervalSeconds = body.pushConfig.pollingInterval,
                    )

                    Result.success(Unit)
                } ?: Result.failure(EnrollmentException("Empty response from server"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(EnrollmentException("Enrollment failed: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build the enrollment request, attempting the pinned-key path
     * first and falling back to HMAC only on specific recoverable
     * failures.
     */
    private suspend fun buildEnrollmentRequest(
        deviceInfo: DeviceInfoCollector.DeviceInfo,
        deviceCode: String,
        timestamp: String,
        method: String,
    ): EnrollmentRequest {
        val pinnedKeyRequest = tryBuildPinnedKeyRequest(
            deviceInfo, deviceCode, timestamp, method,
        )
        if (pinnedKeyRequest != null) return pinnedKeyRequest

        // HMAC fallback.
        val hmacSignature = signatureGenerator.generateEnrollmentSignature(
            model = deviceInfo.model,
            manufacturer = deviceInfo.manufacturer,
            osVersion = deviceInfo.osVersion,
            serialNumber = deviceInfo.serialNumber,
            imei = deviceInfo.imei,
            macAddress = deviceInfo.macAddress,
            androidId = deviceInfo.androidId,
            method = method,
            timestamp = timestamp,
        )

        return EnrollmentRequest(
            model = deviceInfo.model,
            manufacturer = deviceInfo.manufacturer,
            osVersion = deviceInfo.osVersion,
            sdkVersion = deviceInfo.sdkVersion,
            serialNumber = deviceInfo.serialNumber,
            imei = deviceInfo.imei,
            macAddress = deviceInfo.macAddress,
            androidId = deviceInfo.androidId,
            agentVersion = BuildConfig.VERSION_NAME,
            agentPackage = context.packageName,
            method = method,
            timestamp = timestamp,
            signature = hmacSignature,
        )
    }

    /**
     * Attempt the pinned-key path. Returns a fully-signed
     * [EnrollmentRequest] on success, `null` on the two specific
     * recoverable failures (Keystore unavailable, server returns
     * 503 on the challenge endpoint) that should fall back to
     * HMAC. Any other exception propagates.
     */
    private suspend fun tryBuildPinnedKeyRequest(
        deviceInfo: DeviceInfoCollector.DeviceInfo,
        deviceCode: String,
        timestamp: String,
        method: String,
    ): EnrollmentRequest? {
        // Step 1: ensure we have a Keystore keypair. If this fails,
        // we cannot do pinned-key enrollment on this device at all.
        val keyInfo = try {
            deviceIdentity.ensureKeyPair()
        } catch (e: DeviceIdentityException) {
            AgentTelemetryHolder.event(
                "enrollment_pinned_key_unavailable",
                mapOf("reason" to "keystore_unavailable", "error" to (e.message ?: "")),
            )
            return null
        }

        // Step 2: fetch a challenge. 503 means the server doesn't
        // support the pinned-key path — fall back to HMAC. Any
        // other non-2xx is a real failure and should NOT fall
        // through silently; we return null and let the HMAC
        // fallback run, but a telemetry event flags the unusual
        // path.
        val challengeResponse = try {
            mdmApi.fetchEnrollmentChallenge()
        } catch (e: Exception) {
            AgentTelemetryHolder.event(
                "enrollment_pinned_key_unavailable",
                mapOf("reason" to "challenge_network_error", "error" to (e.message ?: "")),
            )
            return null
        }
        if (!challengeResponse.isSuccessful) {
            AgentTelemetryHolder.event(
                "enrollment_pinned_key_unavailable",
                mapOf(
                    "reason" to "challenge_http_${challengeResponse.code()}",
                ),
            )
            return null
        }
        val challenge = challengeResponse.body()?.challenge
            ?: run {
                AgentTelemetryHolder.event(
                    "enrollment_pinned_key_unavailable",
                    mapOf("reason" to "challenge_body_empty"),
                )
                return null
            }

        // Step 3: build the canonical message and sign it with
        // the Keystore private key. Signing failures here are
        // unexpected (the keypair was just validated by
        // ensureKeyPair), so they propagate rather than fall
        // back to HMAC silently.
        val canonical = CanonicalEnrollmentMessage.build(
            publicKey = keyInfo.publicKeySpkiBase64,
            model = deviceInfo.model,
            manufacturer = deviceInfo.manufacturer,
            osVersion = deviceInfo.osVersion,
            serialNumber = deviceInfo.serialNumber,
            imei = deviceInfo.imei,
            macAddress = deviceInfo.macAddress,
            androidId = deviceInfo.androidId,
            method = method,
            timestamp = timestamp,
            challenge = challenge,
        )
        val signature = deviceIdentity.sign(canonical)

        AgentTelemetryHolder.event(
            "enrollment_pinned_key_selected",
            mapOf(
                "security_level" to keyInfo.securityLevel,
                "is_hardware_backed" to keyInfo.isHardwareBacked,
            ),
        )

        return EnrollmentRequest(
            model = deviceInfo.model,
            manufacturer = deviceInfo.manufacturer,
            osVersion = deviceInfo.osVersion,
            sdkVersion = deviceInfo.sdkVersion,
            serialNumber = deviceInfo.serialNumber,
            imei = deviceInfo.imei,
            macAddress = deviceInfo.macAddress,
            androidId = deviceInfo.androidId,
            agentVersion = BuildConfig.VERSION_NAME,
            agentPackage = context.packageName,
            method = method,
            timestamp = timestamp,
            signature = signature,
            publicKey = keyInfo.publicKeySpkiBase64,
            attestationChallenge = challenge,
        )
    }

    private fun generateTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    companion object {
        /** Operator typed a code into the enrollment screen. */
        const val METHOD_MANUAL = "manual"

        /**
         * Enrollment triggered by managed provisioning. The platform does
         * not tell the DPC which vector started setup (QR, NFC, `afw#`,
         * zero-touch), so we report the by-far most common one.
         */
        const val METHOD_PROVISIONED = "qr"
    }
}

/**
 * Exception thrown when enrollment fails.
 */
class EnrollmentException(message: String) : Exception(message)
