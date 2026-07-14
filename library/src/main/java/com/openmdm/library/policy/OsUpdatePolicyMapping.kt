package com.openmdm.library.policy

import com.openmdm.library.device.OsUpdatePolicy

/**
 * Translate the wire/policy form into the platform-facing [OsUpdatePolicy].
 *
 * An unrecognised type falls back to [OsUpdatePolicy.Automatic] rather than
 * throwing: a policy the server sent should never crash the agent applying it,
 * and "install updates automatically" is the safe default to land on.
 */
fun OsUpdatePolicySetting.toOsUpdatePolicy(): OsUpdatePolicy = when (type.lowercase()) {
    "postpone" -> OsUpdatePolicy.Postpone
    "windowed" -> OsUpdatePolicy.Windowed(
        startMinutes = windowStartMinutes ?: 0,
        endMinutes = windowEndMinutes ?: 0,
    )
    else -> OsUpdatePolicy.Automatic
}
