package com.openmdm.library.network

import android.util.Log
import com.openmdm.library.telemetry.MdmTelemetryHolder
import okhttp3.CertificatePinner

/**
 * TLS certificate pinning for the OpenMDM server connection.
 *
 * ## Why this matters
 *
 * Device-pinned-key enrollment is **not secure without TLS pinning**. On first
 * enrollment the device generates a keypair and submits its public key over
 * HTTPS; the server pins that key to the device row. If an attacker can MITM
 * that first connection with their own TLS certificate, they receive the
 * device's public key and pin *themselves* against the attacker's server. After
 * that, the attacker owns the device's identity forever.
 *
 * The HMAC enrollment path survives a first-enroll MITM because the shared
 * secret backstops it. The pinned-key path has no such backstop. **Pinning must
 * ship with pinned-key enrollment**, or the feature is a net security
 * regression.
 *
 * ## Obtaining a pin
 *
 * The pin is the base64 SHA-256 of the server's Subject Public Key Info:
 *
 * ```bash
 * openssl s_client -servername mdm.example.com -connect mdm.example.com:443 </dev/null 2>/dev/null \
 *   | openssl x509 -pubkey -noout \
 *   | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary \
 *   | openssl enc -base64
 * ```
 *
 * The result looks like `sha256/AbCdEf...=`.
 *
 * ## Rotation
 *
 * Certificates expire, and a stale pin bricks the fleet's ability to talk to
 * the server. Always ship a **backup pin** for the certificate you plan to
 * rotate *to*: the client accepts either, so you can rotate the server and then
 * ship a build that drops the old pin. Shipping only one pin means an expiry is
 * an outage you cannot fix remotely — the devices can no longer reach you.
 *
 * ## Usage
 *
 * ```kotlin
 * val pinner = ServerCertificatePinner.create(
 *     host = "mdm.example.com",
 *     primaryPin = "sha256/AbCd...=",
 *     backupPin = "sha256/EfGh...=",   // the cert you will rotate to
 * )
 * OkHttpClient.Builder()
 *     .apply { pinner?.let { certificatePinner(it) } }
 *     .build()
 * ```
 *
 * The `:agent` app reads these values from its `BuildConfig` (populated by
 * gradle properties) — see `AgentCertificatePinner`. A library embedder
 * supplies them however it likes.
 */
object ServerCertificatePinner {

    /**
     * Build a [CertificatePinner], or `null` when pinning is not configured.
     *
     * `null` is a legitimate state for a development build against a local
     * server with a self-signed certificate — the HTTP stack then uses the
     * platform trust store. It is **not** a legitimate state for a release
     * build, and it is logged loudly so that it cannot be missed quietly.
     */
    fun create(
        host: String?,
        primaryPin: String?,
        backupPin: String? = null,
    ): CertificatePinner? {
        val resolvedHost = host?.takeIf { it.isNotBlank() }
        val resolvedPin = primaryPin?.takeIf { it.isNotBlank() }

        if (resolvedHost == null || resolvedPin == null) {
            Log.w(
                TAG,
                "TLS certificate pinning is DISABLED (host=${resolvedHost ?: "unset"}, " +
                    "pin=${if (resolvedPin == null) "unset" else "set"}). " +
                    "Only safe for local development: an unpinned device performing " +
                    "pinned-key enrollment can have its identity captured by a MITM.",
            )
            MdmTelemetryHolder.event(
                "tls_pinning_disabled",
                mapOf(
                    "reason" to if (resolvedHost == null) "no_host" else "no_primary_pin",
                    "host" to (resolvedHost ?: ""),
                ),
            )
            return null
        }

        val resolvedBackup = backupPin?.takeIf { it.isNotBlank() }

        val builder = CertificatePinner.Builder().add(resolvedHost, resolvedPin)
        if (resolvedBackup != null) {
            builder.add(resolvedHost, resolvedBackup)
        }

        Log.i(
            TAG,
            "TLS certificate pinning enabled for $resolvedHost " +
                "(backup pin: ${resolvedBackup != null})",
        )
        MdmTelemetryHolder.event(
            "tls_pinning_enabled",
            mapOf(
                "host" to resolvedHost,
                "has_backup_pin" to (resolvedBackup != null),
            ),
        )
        return builder.build()
    }

    private const val TAG = "ServerCertPinner"
}
