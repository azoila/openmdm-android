package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Certificate management.
 *
 * Two things depend on this and neither worked:
 *
 *  - **Enterprise Wi-Fi.** `EapConfig` declares `caCertificate`,
 *    `clientCertificate` and `clientKey`, and `NetworkManager` only ever applied
 *    `identity` and `anonymousIdentity`. The certificate fields were parsed,
 *    typed, and thrown away — so a WPA2-Enterprise network requiring a client
 *    certificate simply could not be joined, and an EAP network with no CA
 *    pinned validates against nothing.
 *  - **Private PKI.** A fleet talking to an internal server signed by a corporate
 *    CA needs that CA in the trust store. Without `installCaCert`, every device
 *    has to be touched by hand.
 *
 * Certificates are accepted as PEM or base64 DER — whatever the server has,
 * rather than making the server transform it.
 *
 * Requires Device Owner or Profile Owner.
 */
class CertificateManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName,
) {
    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /**
     * Install a CA certificate into the managed trust store.
     *
     * This is what lets a device trust an internal server. It does **not** bypass
     * TLS pinning: a pinned connection still checks the pin, which is the point —
     * a CA in the trust store is a CA an attacker who compromises it can
     * impersonate.
     */
    fun installCaCertificate(pemOrDer: String): Result<Unit> = runCatching {
        require(isManagementOwner()) { "Certificate install requires Device or Profile Owner" }

        val der = decodeCertificate(pemOrDer)
        val installed = dpm.installCaCert(adminComponent, der)
        check(installed) { "The platform rejected the CA certificate" }

        Log.i(TAG, "CA certificate installed")
    }

    /** Is this CA already trusted by the managed trust store? */
    fun hasCaCertificate(pemOrDer: String): Boolean =
        runCatching { dpm.hasCaCertInstalled(adminComponent, decodeCertificate(pemOrDer)) }
            .getOrDefault(false)

    fun removeCaCertificate(pemOrDer: String): Result<Unit> = runCatching {
        dpm.uninstallCaCert(adminComponent, decodeCertificate(pemOrDer))
    }

    /**
     * Install a client certificate and its private key under [alias].
     *
     * This is what a WPA2-Enterprise EAP-TLS network needs, and what a mutual-TLS
     * connection to an internal service needs. The alias is how the certificate is
     * referenced later — by the Wi-Fi config, or by an app granted access to it.
     *
     * [requestAccess] grants *this* app access to the key without prompting the
     * user. On a Device Owner that is the whole point: an unattended kiosk has
     * nobody to tap "allow".
     */
    fun installClientCertificate(
        alias: String,
        certificatePem: String,
        privateKeyPem: String,
        requestAccess: Boolean = true,
    ): Result<Unit> = runCatching {
        require(isManagementOwner()) { "Certificate install requires Device or Profile Owner" }

        val certificate = parseX509(certificatePem)
        val privateKey = parsePrivateKey(privateKeyPem)

        val installed = dpm.installKeyPair(
            adminComponent,
            privateKey,
            arrayOf(certificate),
            alias,
            requestAccess,
        )
        check(installed) { "The platform rejected the key pair for alias '$alias'" }

        Log.i(TAG, "Client certificate installed under alias '$alias'")
    }

    fun removeClientCertificate(alias: String): Result<Unit> = runCatching {
        val removed = dpm.removeKeyPair(adminComponent, alias)
        check(removed) { "No key pair found for alias '$alias'" }
    }

    // ----- parsing -----

    /**
     * Accept PEM or base64 DER.
     *
     * A server that already has a PEM should not have to strip the armour and
     * re-encode it just to hand it to us; making the caller normalise the format
     * is how certificates end up silently mis-installed.
     */
    internal fun decodeCertificate(input: String): ByteArray {
        val stripped = input
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")

        return Base64.decode(stripped, Base64.DEFAULT)
    }

    private fun parseX509(pem: String): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(
            ByteArrayInputStream(decodeCertificate(pem)),
        ) as X509Certificate
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val stripped = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val der = Base64.decode(stripped, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(der)

        // Try the algorithms a fleet actually uses, rather than making the caller
        // tell us which one this key is.
        return sequenceOf("RSA", "EC")
            .mapNotNull { algorithm ->
                runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }.getOrNull()
            }
            .firstOrNull()
            ?: throw IllegalArgumentException(
                "Private key is neither RSA nor EC, or is not in PKCS#8 form",
            )
    }

    private fun isManagementOwner(): Boolean =
        dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)

    companion object {
        private const val TAG = "CertificateManager"

        fun create(context: Context, adminComponent: ComponentName) =
            CertificateManager(context, adminComponent)
    }
}
