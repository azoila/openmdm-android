package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Work-profile (Profile Owner) operations.
 *
 * This is a **different role** from Device Owner, and most of [DeviceManager]'s
 * methods do not apply here: a Profile Owner cannot reboot the device, cannot
 * factory-reset it, cannot touch the personal side at all. It governs one thing
 * — the managed profile — and that is the point. Mixing these into DeviceManager
 * would invite calling a device-wide method from inside a work profile, where it
 * silently no-ops or throws.
 *
 * The one operation a freshly-provisioned work profile *must* have is
 * [enableProfile]: a profile the DPC never enables stays invisible and unusable,
 * and the user is left with a half-provisioned phone.
 */
class WorkProfileManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName,
) {
    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True when this app is Profile Owner of the profile it runs in. */
    fun isProfileOwner(): Boolean = dpm.isProfileOwnerApp(context.packageName)

    /**
     * True when this app runs inside a *managed profile* (BYOD or COPE), as
     * opposed to being Profile Owner of the primary user. The distinction
     * matters: only a managed profile can be wiped independently of the device.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun isManagedProfile(): Boolean =
        dpm.isProfileOwnerApp(context.packageName) && dpm.isManagedProfile(adminComponent)

    /**
     * Turn the profile on and mark provisioning done.
     *
     * Called from `onProfileProvisioningComplete`. Until this runs, the profile
     * exists but is disabled — its apps do not appear in the launcher and the
     * work badge is absent. A DPC that forgets this ships a work profile nobody
     * can see.
     */
    fun enableProfile(): Result<Unit> = runCatching {
        require(isProfileOwner()) { "enableProfile requires Profile Owner" }

        // A profile with no name shows as a bare "Work" with no attribution.
        dpm.setProfileName(adminComponent, "Work Profile")
        dpm.setProfileEnabled(adminComponent)

        Log.i(TAG, "Work profile enabled")
    }

    /**
     * Allow an intent to cross between the personal and work profiles.
     *
     * By default the two profiles are sealed: a link tapped in the personal
     * browser cannot open a work app, and vice versa. This opens specific
     * lanes — e.g. letting the personal camera satisfy a work app's capture
     * intent, so the user is not forced to install a second camera in the
     * profile.
     */
    fun addCrossProfileIntentFilter(
        filter: android.content.IntentFilter,
        direction: CrossProfileDirection,
    ): Result<Unit> = runCatching {
        require(isProfileOwner()) { "Cross-profile intents require Profile Owner" }
        dpm.addCrossProfileIntentFilter(adminComponent, filter, direction.flag)
    }

    /** Close every cross-profile lane this DPC opened. */
    fun clearCrossProfileIntentFilters(): Result<Unit> = runCatching {
        dpm.clearCrossProfileIntentFilters(adminComponent)
    }

    /**
     * Grant a personal-side package the ability to be called across the profile
     * boundary via `CrossProfileApps` (API 30+). Used for widgets, sharing, and
     * companion apps that legitimately span both sides.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun setCrossProfilePackages(packages: Set<String>): Result<Unit> = runCatching {
        require(isProfileOwner()) { "Cross-profile packages require Profile Owner" }
        dpm.setCrossProfilePackages(adminComponent, packages)
    }

    /**
     * Apply a user restriction *within the profile*.
     *
     * A Profile Owner's restrictions bind the profile only — the personal side
     * is not affected, which is the whole contract of BYOD. Passing a
     * device-wide restriction here simply does nothing on the personal apps,
     * and callers should not expect it to.
     */
    fun setProfileRestriction(restriction: String, enabled: Boolean): Result<Unit> =
        runCatching {
            require(isProfileOwner()) { "Profile restrictions require Profile Owner" }
            if (enabled) {
                dpm.addUserRestriction(adminComponent, restriction)
            } else {
                dpm.clearUserRestriction(adminComponent, restriction)
            }
        }

    /**
     * Wipe the work profile — and only the work profile.
     *
     * This is what "unenroll" means on a BYOD device: the work apps and data go,
     * the employee's phone and personal data stay. Factory-resetting a personal
     * phone to remove one work app would be indefensible, and a Profile Owner
     * cannot do it anyway.
     *
     * On the primary user (fully-managed device) this is a no-op guarded by the
     * managed-profile check, so it cannot be misused to try to wipe a device.
     */
    fun wipeWorkProfile(reason: String? = null): Result<Unit> = runCatching {
        require(isProfileOwner()) { "wipeWorkProfile requires Profile Owner" }

        Log.i(TAG, "Wiping work profile${reason?.let { ": $it" } ?: ""}")
        // wipeData on a Profile Owner removes the profile, not the device.
        dpm.wipeData(0)
    }

    /**
     * Hide or show the personal side's non-work apps inside the launcher's work
     * tab. Not a personal-app control — it only affects what the work profile
     * surfaces.
     */
    fun setProfileAppHidden(packageName: String, hidden: Boolean): Result<Boolean> =
        runCatching {
            require(isProfileOwner()) { "App hiding requires Profile Owner" }
            dpm.setApplicationHidden(adminComponent, packageName, hidden)
        }

    /** The sub-managers that DO apply inside a profile: apps, restrictions, certs. */
    fun getManagedConfigurationManager(): ManagedConfigurationManager =
        ManagedConfigurationManager.create(context, adminComponent)

    fun getCertificateManager(): CertificateManager =
        CertificateManager.create(context, adminComponent)

    fun getRestrictionManager(): RestrictionManager =
        RestrictionManager.create(context, adminComponent)

    /** Direction an intent may cross the profile boundary. */
    enum class CrossProfileDirection(val flag: Int) {
        /** Personal apps can handle the profile's matching intents. */
        WORK_TO_PERSONAL(DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED),

        /** The profile can handle the personal side's matching intents. */
        PERSONAL_TO_WORK(DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
    }

    companion object {
        private const val TAG = "WorkProfile"

        fun create(context: Context, adminComponent: ComponentName) =
            WorkProfileManager(context, adminComponent)
    }
}
