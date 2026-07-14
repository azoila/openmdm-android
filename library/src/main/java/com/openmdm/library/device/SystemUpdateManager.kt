package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.app.admin.FreezePeriod
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import java.time.MonthDay

/** When OS updates are allowed to install. */
sealed class OsUpdatePolicy {
    /** Install as soon as an update is available. */
    object Automatic : OsUpdatePolicy()

    /**
     * Install only inside a nightly window, given in minutes from midnight
     * local time. A kiosk in a shop should reboot at 3am, not mid-transaction.
     */
    data class Windowed(val startMinutes: Int, val endMinutes: Int) : OsUpdatePolicy()

    /**
     * Defer updates for up to 30 days (the platform's hard cap).
     *
     * A deferral is not a block: after 30 days the platform installs anyway.
     * There is no way to postpone a security update indefinitely, and there
     * should not be.
     */
    object Postpone : OsUpdatePolicy()
}

/**
 * OS update control.
 *
 * Without this, a fleet of kiosks reboots for an OS update whenever Google
 * decides — mid-shift, mid-transaction, in the middle of the retail day. Every
 * serious MDM controls this; OpenMDM had no support for it.
 *
 * Requires Device Owner. API 23+ for the policy, API 28+ for freeze periods.
 */
class SystemUpdateManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName,
) {
    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun setPolicy(policy: OsUpdatePolicy): Result<Unit> = runCatching {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "OS update policy requires Device Owner"
        }

        val platformPolicy = when (policy) {
            is OsUpdatePolicy.Automatic -> SystemUpdatePolicy.createAutomaticInstallPolicy()
            is OsUpdatePolicy.Postpone -> SystemUpdatePolicy.createPostponeInstallPolicy()
            is OsUpdatePolicy.Windowed -> SystemUpdatePolicy.createWindowedInstallPolicy(
                policy.startMinutes,
                policy.endMinutes,
            )
        }

        dpm.setSystemUpdatePolicy(adminComponent, platformPolicy)
        Log.i(TAG, "OS update policy set: $policy")
    }

    /**
     * Block OS updates entirely for a recurring calendar period — a retail
     * freeze over the holidays, say.
     *
     * The platform enforces its own limits and will reject anything outside
     * them: a freeze may not exceed 90 days, and consecutive freezes must be at
     * least 60 days apart. That is deliberate on Android's part — a device that
     * can be frozen forever is a device that never gets a security patch — and
     * we surface the rejection rather than swallowing it.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun setFreezePeriods(periods: List<Pair<MonthDay, MonthDay>>): Result<Unit> = runCatching {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "Freeze periods require Android 9+"
        }
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "OS update policy requires Device Owner"
        }

        val current = dpm.getSystemUpdatePolicy()
            ?: SystemUpdatePolicy.createAutomaticInstallPolicy()

        current.setFreezePeriods(periods.map { (start, end) -> FreezePeriod(start, end) })
        dpm.setSystemUpdatePolicy(adminComponent, current)

        Log.i(TAG, "OS update freeze periods set (${periods.size})")
    }

    /** Clear the policy, returning the device to the platform default. */
    fun clearPolicy(): Result<Unit> = runCatching {
        dpm.setSystemUpdatePolicy(adminComponent, null)
    }

    companion object {
        private const val TAG = "SystemUpdate"

        fun create(context: Context, adminComponent: ComponentName) =
            SystemUpdateManager(context, adminComponent)
    }
}
