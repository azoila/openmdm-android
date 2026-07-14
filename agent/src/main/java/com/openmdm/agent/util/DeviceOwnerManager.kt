package com.openmdm.agent.util

import android.content.Context
import com.openmdm.agent.receiver.MDMDeviceAdminReceiver
import com.openmdm.library.device.DeviceManager
import com.openmdm.library.device.HardwareManager
import com.openmdm.library.device.KioskManager
import com.openmdm.library.device.CertificateManager
import com.openmdm.library.device.LauncherManager
import com.openmdm.library.device.ManagedConfigurationManager
import com.openmdm.library.device.SystemUpdateManager
import com.openmdm.library.device.NetworkManager
import com.openmdm.library.device.RestrictionManager
import com.openmdm.library.device.ScreenManager
import com.openmdm.library.device.SecureUpdateParams
import com.openmdm.library.device.UpdateResult
import com.openmdm.library.file.FileDeploymentManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's Device Owner surface — now a thin wrapper over the library's
 * [DeviceManager].
 *
 * This class used to be a 965-line near-duplicate of `DeviceManager`: the same
 * ~29 methods, the same DevicePolicyManager calls, maintained twice. Two copies
 * of anything drift, and these had:
 *
 *  - **`setAdbEnabled(true)` did not enable ADB.** The agent's copy called
 *    `addUserRestriction(admin, null)` on the enable path — passing a *null
 *    restriction* instead of *clearing* the restriction. The library's copy did
 *    it correctly with `clearUserRestriction`. Collapsing onto one
 *    implementation fixes this for free.
 *  - `wipeDevice(preserveData)` mapped `true` to `WIPE_EXTERNAL_STORAGE`, which
 *    wipes *more*, not less. The flag now means what it says.
 *  - Only the agent's copy verified APK hashes, so the *published library* — the
 *    thing we tell people to embed — installed APKs unverified. Fixed in #15,
 *    and now there is only one path to keep honest.
 *
 * Everything the agent needs and the library did not have (secure self-update,
 * launcher access) moved into the library rather than being kept here, so an
 * embedder gets it too. What stays agent-specific is exactly what should:
 * the admin component identity and the install-result broadcast actions.
 */
@Singleton
class DeviceOwnerManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Re-exported so existing agent call sites keep compiling. */
    companion object {
        const val INSTALL_RESULT_ACTION = "com.openmdm.agent.INSTALL_RESULT"
        const val UNINSTALL_RESULT_ACTION = "com.openmdm.agent.UNINSTALL_RESULT"
    }

    private val delegate: DeviceManager = DeviceManager.create(
        context = context,
        adminReceiverClass = MDMDeviceAdminReceiver::class.java,
        installResultAction = INSTALL_RESULT_ACTION,
        uninstallResultAction = UNINSTALL_RESULT_ACTION,
    )

    // ----- Ownership -----

    fun isDeviceOwner(): Boolean = delegate.isDeviceOwner()

    fun isDeviceAdmin(): Boolean = delegate.isDeviceAdmin()

    fun isProfileOwner(): Boolean = delegate.isProfileOwner()

    // ----- Device control -----

    fun lockDevice(): Result<Unit> = delegate.lockDevice()

    fun rebootDevice(): Result<Unit> = delegate.rebootDevice()

    /**
     * Factory-reset the device.
     *
     * [preserveData] `false` (the default) wipes external storage too. The old
     * agent copy had this backwards: it mapped `preserveData = true` onto
     * `WIPE_EXTERNAL_STORAGE`, so asking to *preserve* data wiped *more* of it.
     */
    fun wipeDevice(preserveData: Boolean = false): Result<Unit> {
        val flags = if (preserveData) 0 else android.app.admin.DevicePolicyManager.WIPE_EXTERNAL_STORAGE
        return delegate.wipeDevice(flags)
    }

    // ----- Apps -----

    suspend fun installApkSilently(
        apkUrl: String,
        packageName: String,
        expectedSha256: String? = null,
        requireHash: Boolean = false,
    ): Result<Unit> = delegate.installApkSilently(
        apkUrl = apkUrl,
        packageName = packageName,
        resultReceiverClass = InstallResultReceiver::class.java,
        expectedSha256 = expectedSha256,
        requireHash = requireHash,
    )

    suspend fun installApkSecurely(params: SecureUpdateParams): UpdateResult =
        delegate.installApkSecurely(params, InstallResultReceiver::class.java)

    fun uninstallAppSilently(packageName: String): Result<Unit> =
        delegate.uninstallAppSilently(packageName)

    /** Restore state saved before a self-update. Called from BootReceiver. */
    fun restoreAgentState(): Boolean = runCatching { delegate.restoreAppState() }.isSuccess

    // ----- Permissions -----

    fun grantPermission(packageName: String, permission: String): Result<Unit> =
        delegate.grantPermission(packageName, permission)

    fun grantPermissions(
        packageName: String,
        permissions: List<String>,
    ): Result<Map<String, Boolean>> = runCatching {
        delegate.grantPermissions(packageName, permissions)
    }

    fun grantCommonPermissions(packageName: String): Result<Unit> =
        delegate.grantCommonPermissions(packageName)

    // ----- Battery -----

    fun whitelistFromBatteryOptimization(packageName: String): Result<Unit> =
        delegate.whitelistFromBatteryOptimization(packageName)

    fun isWhitelistedFromBatteryOptimization(packageName: String): Boolean =
        delegate.isWhitelistedFromBatteryOptimization(packageName)

    // ----- Kiosk -----

    fun setLockTaskPackages(packages: List<String>): Result<Unit> =
        delegate.setLockTaskPackages(packages)

    fun startLockTaskMode(packageName: String): Result<Unit> =
        delegate.startLockTaskMode(packageName)

    // ----- Hardware / restrictions -----

    fun setScreenBrightness(level: Int): Result<Unit> = delegate.setScreenBrightness(level)

    fun setVolume(streamType: Int, volume: Int): Result<Unit> =
        delegate.setVolume(streamType, volume)

    /**
     * Enable or disable ADB.
     *
     * The agent's own implementation of this **did not work**: it called
     * `addUserRestriction(admin, null)` to enable, passing a null restriction
     * rather than clearing `DISALLOW_DEBUGGING_FEATURES`. Delegating to the
     * library's correct implementation fixes it.
     */
    fun setAdbEnabled(enabled: Boolean): Result<Unit> = delegate.setAdbEnabled(enabled)

    fun setUserRestriction(restriction: String, enabled: Boolean): Result<Unit> =
        delegate.setUserRestriction(restriction, enabled)

    fun setCameraDisabled(disabled: Boolean): Result<Unit> = delegate.setCameraDisabled(disabled)

    fun setPasswordQuality(quality: Int): Result<Unit> = delegate.setPasswordQuality(quality)

    fun setPasswordMinimumLength(length: Int): Result<Unit> =
        delegate.setPasswordMinimumLength(length)

    /** Apply the full password policy from a PolicySettings. Returns per-rule failures. */
    fun applyPasswordPolicy(settings: com.openmdm.library.policy.PolicySettings): Map<String, Throwable> =
        delegate.applyPasswordPolicy(
            quality = settings.passwordQuality,
            minimumLength = settings.passwordMinLength,
            minimumLetters = settings.passwordMinLetters,
            minimumNumeric = settings.passwordMinNumeric,
            minimumSymbols = settings.passwordMinSymbols,
            minimumUpperCase = settings.passwordMinUpperCase,
            minimumLowerCase = settings.passwordMinLowerCase,
            expirationDays = settings.passwordExpirationDays,
            historyLength = settings.passwordHistoryLength,
            maximumFailedAttempts = settings.maxFailedPasswordAttempts,
        )

    // ----- Sub-managers -----

    fun getHardwareManager(): HardwareManager = delegate.getHardwareManager()

    fun getScreenManager(): ScreenManager = delegate.getScreenManager()

    fun getKioskManager(): KioskManager = delegate.getKioskManager()

    fun getRestrictionManager(): RestrictionManager = delegate.getRestrictionManager()

    fun getNetworkManager(): NetworkManager = delegate.getNetworkManager()

    fun getFileDeploymentManager(): FileDeploymentManager = delegate.getFileDeploymentManager()

    fun getLauncherManager(): LauncherManager = delegate.getLauncherManager()

    fun getManagedConfigurationManager(): ManagedConfigurationManager =
        delegate.getManagedConfigurationManager()

    fun getSystemUpdateManager(): SystemUpdateManager = delegate.getSystemUpdateManager()

    fun getCertificateManager(): CertificateManager = delegate.getCertificateManager()

    fun destroy() = delegate.destroy()
}
