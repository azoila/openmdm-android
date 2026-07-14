package com.openmdm.library.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pinning configuration.
 *
 * This code shipped for months returning `null` unconditionally, because it read
 * BuildConfig fields the gradle build never defined — so TLS pinning was OFF in
 * every build while looking, from the source, entirely present. The tests below
 * pin the contract that made that possible to miss: what "configured" means, and
 * what happens when it isn't.
 */
class ServerCertificatePinnerTest {

    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val backup = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    @Test
    fun `returns a pinner when host and pin are configured`() {
        val pinner = ServerCertificatePinner.create("mdm.example.com", pin)

        assertThat(pinner).isNotNull()
        assertThat(pinner!!.findMatchingPins("mdm.example.com")).isNotEmpty()
    }

    @Test
    fun `accepts a backup pin so a cert rotation is not an outage`() {
        // Shipping only one pin means a certificate expiry is an outage you
        // cannot fix remotely — the devices can no longer reach you to be fixed.
        val pinner = ServerCertificatePinner.create("mdm.example.com", pin, backup)

        assertThat(pinner!!.findMatchingPins("mdm.example.com")).hasSize(2)
    }

    @Test
    fun `is disabled when the host is missing`() {
        assertThat(ServerCertificatePinner.create(null, pin)).isNull()
        assertThat(ServerCertificatePinner.create("", pin)).isNull()
    }

    @Test
    fun `is disabled when the pin is missing`() {
        assertThat(ServerCertificatePinner.create("mdm.example.com", null)).isNull()
        assertThat(ServerCertificatePinner.create("mdm.example.com", "  ")).isNull()
    }

    @Test
    fun `does not pin a host it was not configured for`() {
        val pinner = ServerCertificatePinner.create("mdm.example.com", pin)

        assertThat(pinner!!.findMatchingPins("evil.example.com")).isEmpty()
    }
}
