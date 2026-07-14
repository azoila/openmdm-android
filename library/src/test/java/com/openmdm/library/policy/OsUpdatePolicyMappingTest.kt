package com.openmdm.library.policy

import com.google.common.truth.Truth.assertThat
import com.openmdm.library.device.OsUpdatePolicy
import org.junit.Test

class OsUpdatePolicyMappingTest {

    @Test
    fun `maps automatic`() {
        assertThat(OsUpdatePolicySetting("automatic").toOsUpdatePolicy())
            .isInstanceOf(OsUpdatePolicy.Automatic::class.java)
    }

    @Test
    fun `maps postpone`() {
        assertThat(OsUpdatePolicySetting("postpone").toOsUpdatePolicy())
            .isInstanceOf(OsUpdatePolicy.Postpone::class.java)
    }

    @Test
    fun `maps windowed with its window`() {
        val mapped = OsUpdatePolicySetting("windowed", 120, 300).toOsUpdatePolicy()
        assertThat(mapped).isEqualTo(OsUpdatePolicy.Windowed(120, 300))
    }

    @Test
    fun `an unrecognised type falls back to automatic, not a crash`() {
        // A policy the server sent should never crash the agent applying it, and
        // "install updates automatically" is the safe default to land on.
        assertThat(OsUpdatePolicySetting("nonsense").toOsUpdatePolicy())
            .isInstanceOf(OsUpdatePolicy.Automatic::class.java)
    }
}
