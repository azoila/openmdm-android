package com.openmdm.agent.network

import android.util.Log
import com.openmdm.agent.BuildConfig
import com.openmdm.agent.telemetry.AgentTelemetryHolder
import okhttp3.CertificatePinner

/**
 * TLS certificate pinning for the openmdm server connection.
 *
 * ## Why this matters for Phase 2b
 *
 * Phase 2b device-pinned-key enrollment is **not secure without
 * TLS pinning**. On first enrollment the agent generates a
 * keypair and submits its public key over HTTPS — the server
 * pins that public key on the device row. If an attacker can
 * MITM the first-enroll connection with their own TLS certificate,
 * they receive the device's public key and can pin themselves
 * against the attacker's server instead of the real one. After
 * that, the attacker owns the device's identity forever.
 *
 * The Phase 2a HMAC path survives a first-enroll MITM because
 * the HMAC secret is shared and the attacker can't forge it, but
 * the pinned-key path does not have that backstop. **TLS pinning
 * must land in the same release that turns on the pinned-key
 * enrollment flow**, otherwise the feature is a net security
 * regression.
 *
 * ## How to obtain the pin
 *
 * The pin is the base64-encoded SHA-256 hash of the server's
 * Subject Public Key Info (SPKI). Generate it with OpenSSL:
 *
 * ```bash
 * openssl s_client -servername mdm.example.com \
 *     -connect mdm.example.com:443 </dev/null 2>/dev/null \
 *   | openssl x509 -pubkey -noout \
 *   | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary \
 *   | openssl enc -base64
 * ```
 *
 * The result looks like `sha256/AbCdEfGhIjKlMnOpQrStUvWxYz...=`.
 *
 * ## How to supply the pin to the build
 *
 * Pass a gradle property at build time:
 *
 * ```bash
 * ./gradlew :agent:assembleRelease \
 *     -PopenmdmServerPin="sha256/AbCdEfGhIjKlMnOp...=" \
 *     -PopenmdmServerHost="mdm.example.com"
 * ```
 *
 * The gradle build pushes both into `BuildConfig.MDM_SERVER_PIN`
 * and `BuildConfig.MDM_SERVER_HOST`. When either is empty, pinning
 * is **disabled** and the agent uses the system trust store, so a
 * freshly-cloned dev build can hit a local openmdm without
 * certificate errors.
 *
 * **Release builds cannot be assembled unpinned**: the release build
 * type in `agent/build.gradle.kts` fails if `-PmdmServerHost` and
 * `-PmdmServerPin` are not supplied (override with
 * `-PallowUnpinnedRelease=true` only for an emergency build).
 *
 * ## Rotation
 *
 * You will eventually rotate the server certificate, and the old
 * pin will become invalid. Two options:
 *
 * 1. **Ship a backup pin alongside the primary**, pointing at the
 *    *next* certificate you plan to rotate to. The agent trusts
 *    either. Rotate the server, then ship the next APK that drops
 *    the old pin. This is the standard operational pattern.
 * 2. **Ship an emergency unpinned build** if you forgot to ship
 *    the backup pin and the cert has already expired. This is the
 *    "we're in trouble, buy us time" path.
 *
 * Backup pins are configured via `openmdmServerPinBackup` in the
 * same way as the primary — set the gradle property, rebuild, ship.
 *
 * ## Wildcard hostnames
 *
 * OkHttp's `CertificatePinner.Builder.add()` accepts a wildcard
 * like `*.mdm.example.com`. The pin still applies only to leaf
 * certificates whose SPKI matches the configured hash, but the
 * wildcard lets you use the same pin for staging + prod
 * subdomains. Supply a bare host like `mdm.example.com` to pin
 * exactly that one hostname.
 */
object ServerCertificatePinner {

    /**
     * Build an [okhttp3.CertificatePinner] from `BuildConfig` if
     * the host and at least one pin are configured, or return
     * `null` if pinning is disabled for this build. Returning null
     * is a legitimate state for dev builds against local servers
     * with self-signed certs — the rest of the HTTP stack uses
     * the platform trust store.
     */
    fun fromBuildConfig(): CertificatePinner? {
        val host = BuildConfig.MDM_SERVER_HOST.takeIf { it.isNotBlank() }
        val primaryPin = BuildConfig.MDM_SERVER_PIN.takeIf { it.isNotBlank() }

        if (host == null || primaryPin == null) {
            // Release builds cannot reach this branch — the gradle release
            // build type refuses to assemble without a host and a pin. So
            // this is a debug build against a local server, which is a
            // legitimate unpinned configuration. It is still logged loudly:
            // an unpinned agent that performs pinned-key enrollment can have
            // its identity captured by a first-enroll MITM.
            Log.w(
                TAG,
                "TLS certificate pinning is DISABLED (host=${host ?: "unset"}, " +
                    "pin=${if (primaryPin == null) "unset" else "set"}). " +
                    "This is only safe for local development.",
            )
            AgentTelemetryHolder.event(
                "tls_pinning_disabled",
                mapOf(
                    "reason" to if (host == null) "no_host" else "no_primary_pin",
                    "host" to (host ?: ""),
                ),
            )
            return null
        }

        val backupPin = BuildConfig.MDM_SERVER_PIN_BACKUP.takeIf { it.isNotBlank() }

        val builder = CertificatePinner.Builder().add(host, primaryPin)
        if (backupPin != null) {
            builder.add(host, backupPin)
        }

        Log.i(TAG, "TLS certificate pinning enabled for $host (backup pin: ${backupPin != null})")
        AgentTelemetryHolder.event(
            "tls_pinning_enabled",
            mapOf(
                "host" to host,
                "has_backup_pin" to (backupPin != null),
            ),
        )
        return builder.build()
    }

    private const val TAG = "ServerCertPinner"
}
