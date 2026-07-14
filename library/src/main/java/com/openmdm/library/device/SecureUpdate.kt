package com.openmdm.library.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.openmdm.library.security.ApkIntegrity
import com.openmdm.library.telemetry.MdmTelemetryHolder
import java.io.File

/** What to install, and what must be true before we do. */
data class SecureUpdateParams(
    val apkUrl: String,
    val packageName: String,
    /** Hex SHA-256 of the APK. Not optional here: a *secure* update verifies. */
    val expectedSha256: String,
    val targetVersion: String,
    val targetVersionCode: Int,
    /**
     * Refuse the update unless the currently-installed version is at least this.
     * Guards a migration that cannot be skipped.
     */
    val minPreviousVersion: String? = null,
    /**
     * True when the app is updating *itself*. The process is about to be
     * replaced, so its state is backed up first.
     */
    val isSelfUpdate: Boolean = false,
)

data class UpdateResult(
    val success: Boolean,
    val message: String,
    val fromVersion: String? = null,
    val toVersion: String? = null,
    val error: String? = null,
)

/**
 * Version comparison and update pre-flight checks.
 *
 * Split out of [DeviceManager] so the parts that do not need a device — version
 * ordering, downgrade detection — can actually be tested. Burying this logic
 * behind `DevicePolicyManager` is why it never was.
 */
internal object SecureUpdate {

    private const val TAG = "SecureUpdate"

    /** The installed version of [packageName], or `0.0.0` when it is not installed. */
    fun installedVersion(
        packageManager: PackageManager,
        packageName: String,
    ): Pair<String, Int> = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        Pair(info.versionName ?: "unknown", code)
    } catch (_: PackageManager.NameNotFoundException) {
        // Not installed. "0.0.0" rather than null, so a first install is just an
        // update from nothing and every version comparison below still holds.
        Pair("0.0.0", 0)
    }

    /**
     * Compare dotted numeric versions. Negative when [a] < [b].
     *
     * An unparseable part is treated as *older*, never as equal. Treating
     * `1.2.0-rc` as "same as target" would mean the device is silently never
     * updated and nobody finds out.
     */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split(".")
        val right = b.split(".")

        for (i in 0 until maxOf(left.size, right.size)) {
            // An ABSENT trailing part is zero: "1.2" and "1.2.0" are the same
            // version. An UNPARSEABLE part is a different thing entirely, and is
            // treated as older — treating "1.2.0-rc" as "same as target" would
            // mean the device is silently never updated and nobody finds out.
            val x = left.getOrNull(i)?.let { it.toIntOrNull() ?: return -1 } ?: 0
            val y = right.getOrNull(i)?.let { it.toIntOrNull() ?: return 1 } ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * Why this update must not proceed, or `null` if it may.
     *
     * Downgrade protection is the load-bearing check: silently installing an
     * older build over a newer one is how a fleet gets rolled backwards by a
     * stale command that was queued weeks ago and only just reached the device.
     */
    fun rejectionReason(
        params: SecureUpdateParams,
        currentVersion: String,
        currentVersionCode: Int,
    ): UpdateResult? {
        if (currentVersionCode > 0 && params.targetVersionCode <= currentVersionCode) {
            return UpdateResult(
                success = false,
                message = "Refusing to install ${params.targetVersion} over $currentVersion",
                fromVersion = currentVersion,
                toVersion = params.targetVersion,
                error = "DOWNGRADE_BLOCKED",
            )
        }

        params.minPreviousVersion?.let { minimum ->
            if (compareVersions(currentVersion, minimum) < 0) {
                return UpdateResult(
                    success = false,
                    message = "Installed $currentVersion is below the required minimum $minimum",
                    fromVersion = currentVersion,
                    toVersion = params.targetVersion,
                    error = "MIN_VERSION_NOT_MET",
                )
            }
        }

        return null
    }

    /**
     * Copy the app's shared preferences aside before it replaces itself.
     *
     * A self-update kills the process and swaps the APK underneath it. If the new
     * build cannot read the old state — a corrupted write, an interrupted
     * install — the device comes back up as a factory-fresh agent that has
     * forgotten it was ever enrolled. Restoring from this copy is the difference
     * between a hiccup and a truck roll.
     */
    fun backupAppState(context: Context) {
        try {
            val backupDir = File(context.filesDir, BACKUP_DIR).apply { mkdirs() }
            val prefsDir = File(context.dataDir, "shared_prefs")
            if (!prefsDir.exists()) return

            prefsDir.listFiles()?.forEach { file ->
                file.copyTo(File(backupDir, file.name), overwrite = true)
            }
            MdmTelemetryHolder.event("self_update_state_backed_up")
        } catch (t: Throwable) {
            // A failed backup must not block the update — but it must be visible,
            // because it means the safety net is not there if the update goes wrong.
            Log.w(TAG, "Failed to back up app state before self-update", t)
            MdmTelemetryHolder.nonFatal(t, "self_update_backup_failed")
        }
    }

    /** Restore what [backupAppState] saved. Called on first boot after a self-update. */
    fun restoreAppState(context: Context) {
        try {
            val backupDir = File(context.filesDir, BACKUP_DIR)
            if (!backupDir.exists()) return

            val prefsDir = File(context.dataDir, "shared_prefs").apply { mkdirs() }
            backupDir.listFiles()?.forEach { file ->
                file.copyTo(File(prefsDir, file.name), overwrite = true)
            }
            MdmTelemetryHolder.event("self_update_state_restored")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to restore app state after self-update", t)
            MdmTelemetryHolder.nonFatal(t, "self_update_restore_failed")
        }
    }

    /** Verify a downloaded APK against its expected digest. */
    fun verify(apk: File, expectedSha256: String): Boolean =
        ApkIntegrity.matches(apk, expectedSha256)

    private const val BACKUP_DIR = "openmdm_state_backup"
}
