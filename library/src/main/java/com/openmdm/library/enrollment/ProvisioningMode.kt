package com.openmdm.library.enrollment

/**
 * How a device is managed.
 *
 * OpenMDM began as fully-managed-device only — kiosks, signage, dedicated
 * hardware, where the app is **Device Owner** and controls the whole device.
 * That is the wrong model for a phone someone also uses personally: you cannot
 * (and must not) factory-reset an employee's own phone to manage the one work
 * app on it.
 *
 * A **work profile** is the answer. The work app is **Profile Owner** of a
 * separate, encrypted profile that holds only work apps and data; the personal
 * side is untouched and invisible to the DPC. "Wipe" wipes the profile, not the
 * phone.
 */
enum class ProvisioningMode {
    /**
     * The DPC owns the entire device (Device Owner). Established by QR, NFC,
     * `afw#`, or zero-touch on a factory-fresh device. Kiosks and dedicated
     * hardware.
     */
    FULLY_MANAGED_DEVICE,

    /**
     * A managed work profile on an otherwise-personal device (Profile Owner).
     * BYOD — the employee keeps their phone, the company gets a walled garden.
     * The personal side is not visible to, or controllable by, the DPC.
     */
    WORK_PROFILE,

    /**
     * Company-Owned, Personally-Enabled: a company device (Device Owner) that
     * also carries a work profile, so the user gets a personal space on hardware
     * the company still fully controls. The DPC can wipe the whole device *or*
     * just the profile.
     */
    COMPANY_OWNED_PERSONALLY_ENABLED,
    ;

    companion object {
        /**
         * Parse a mode from a policy/config string, defaulting to
         * fully-managed. An unrecognised value is NOT an error the enrollment
         * should die on — it falls back to the historical behaviour.
         */
        fun fromString(value: String?): ProvisioningMode = when (value?.lowercase()) {
            "work_profile", "managed_profile", "profile" -> WORK_PROFILE
            "cope", "company_owned_personally_enabled" -> COMPANY_OWNED_PERSONALLY_ENABLED
            else -> FULLY_MANAGED_DEVICE
        }
    }
}
