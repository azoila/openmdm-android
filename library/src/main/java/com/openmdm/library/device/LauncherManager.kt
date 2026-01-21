package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Launcher Manager
 *
 * Manages app visibility and launcher preferences for MDM deployments.
 * Provides controls for hiding/showing apps and setting the default launcher.
 *
 * Features:
 * - Hide/show individual apps
 * - Suspend/unsuspend apps (grayed out but visible)
 * - Apply visibility policies (allowlist/blocklist)
 * - Set MDM app as default launcher
 * - Query launchable apps with filtering
 *
 * Usage:
 * ```kotlin
 * val launcherManager = LauncherManager.create(context, adminComponent)
 * launcherManager.hideApp("com.example.blocked")
 * launcherManager.setAsDefaultLauncher(ComponentName(context, LauncherActivity::class.java))
 * ```
 */
class LauncherManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val packageManager: PackageManager = context.packageManager

    companion object {
        /**
         * Create LauncherManager with a DeviceAdminReceiver class
         */
        fun <T : android.app.admin.DeviceAdminReceiver> create(
            context: Context,
            adminReceiverClass: Class<T>
        ): LauncherManager {
            val adminComponent = ComponentName(context, adminReceiverClass)
            return LauncherManager(context, adminComponent)
        }

        /**
         * Create LauncherManager with explicit ComponentName
         */
        fun create(context: Context, adminComponent: ComponentName): LauncherManager {
            return LauncherManager(context, adminComponent)
        }
    }

    // ============================================
    // Status Checks
    // ============================================

    private fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(context.packageName)

    // ============================================
    // App Visibility - Hide/Show
    // ============================================

    /**
     * Hide an app from the launcher.
     *
     * The app will be completely hidden - it won't appear in the launcher
     * or app drawer, but its data is preserved.
     *
     * @param packageName The package name of the app to hide
     * @return Result indicating success or failure
     */
    fun hideApp(packageName: String): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Hiding apps requires Device Owner" }
        require(packageName != context.packageName) { "Cannot hide the MDM app" }

        val success = devicePolicyManager.setApplicationHidden(
            adminComponent,
            packageName,
            true
        )
        require(success) { "Failed to hide app: $packageName" }
    }

    /**
     * Show a previously hidden app.
     *
     * @param packageName The package name of the app to show
     * @return Result indicating success or failure
     */
    fun showApp(packageName: String): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Showing apps requires Device Owner" }

        val success = devicePolicyManager.setApplicationHidden(
            adminComponent,
            packageName,
            false
        )
        require(success) { "Failed to show app: $packageName" }
    }

    /**
     * Check if an app is hidden.
     *
     * @param packageName The package name to check
     * @return true if the app is hidden, false otherwise
     */
    fun isAppHidden(packageName: String): Boolean {
        return try {
            devicePolicyManager.isApplicationHidden(adminComponent, packageName)
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Get list of all hidden apps.
     *
     * @return List of package names that are currently hidden
     */
    fun getHiddenApps(): List<String> {
        if (!isDeviceOwner()) return emptyList()

        return getAllInstalledPackages()
            .filter { isAppHidden(it) }
    }

    // ============================================
    // App Visibility - Suspend/Unsuspend
    // ============================================

    /**
     * Suspend apps (grayed out in launcher, cannot be launched).
     *
     * Suspended apps appear in the launcher but are grayed out and
     * cannot be started. This is less restrictive than hiding.
     *
     * @param packageNames List of package names to suspend
     * @return Result containing list of packages that failed to suspend
     */
    fun suspendApps(packageNames: List<String>): Result<List<String>> = runCatching {
        require(isDeviceOwner()) { "Suspending apps requires Device Owner" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { "Suspend requires Android N+" }

        val filtered = packageNames.filter { it != context.packageName }
        val failed = devicePolicyManager.setPackagesSuspended(
            adminComponent,
            filtered.toTypedArray(),
            true
        )
        failed?.toList() ?: emptyList()
    }

    /**
     * Unsuspend previously suspended apps.
     *
     * @param packageNames List of package names to unsuspend
     * @return Result indicating success or failure
     */
    fun unsuspendApps(packageNames: List<String>): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Unsuspending apps requires Device Owner" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { "Unsuspend requires Android N+" }

        devicePolicyManager.setPackagesSuspended(
            adminComponent,
            packageNames.toTypedArray(),
            false
        )
    }

    /**
     * Check if an app is suspended.
     *
     * @param packageName The package name to check
     * @return true if the app is suspended, false otherwise
     */
    fun isAppSuspended(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                devicePolicyManager.isPackageSuspended(adminComponent, packageName)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // ============================================
    // Visibility Policy Application
    // ============================================

    /**
     * Apply app visibility policy.
     *
     * @param allowedApps List of apps that should be visible (only used in ALLOWLIST mode)
     * @param blockedApps List of apps that should be hidden (only used in BLOCKLIST mode)
     * @param mode The visibility mode to apply
     * @return Result indicating success or failure
     */
    fun applyVisibilityPolicy(
        allowedApps: List<String>,
        blockedApps: List<String>,
        mode: VisibilityMode
    ): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Visibility policy requires Device Owner" }

        when (mode) {
            VisibilityMode.DEFAULT -> {
                // Show all apps - unhide everything
                getHiddenApps().forEach { showApp(it) }
            }

            VisibilityMode.ALLOWLIST -> {
                // Hide all apps except those in allowedApps
                // NOTE: Do NOT exempt all system packages - only critical system UI
                // This is a kiosk mode, so we want strict control over visible apps
                val alwaysAllowed = setOf(
                    context.packageName,  // MDM app must always be visible
                    "com.android.systemui"  // Required for system UI
                )

                // First, unhide any allowed apps that are currently hidden
                // This is important because hidden apps don't show up in getAllLaunchablePackages()
                getHiddenApps().forEach { packageName ->
                    if (packageName in allowedApps || packageName in alwaysAllowed) {
                        showApp(packageName)
                    }
                }

                // Also explicitly try to show all allowed apps (in case they were hidden)
                allowedApps.forEach { packageName ->
                    try {
                        showApp(packageName)
                    } catch (e: Exception) {
                        // App might not be installed, ignore
                    }
                }

                // Now hide all non-allowed launchable apps
                getAllLaunchablePackages().forEach { packageName ->
                    val shouldBeVisible = packageName in allowedApps ||
                            packageName in alwaysAllowed

                    if (!shouldBeVisible) {
                        hideApp(packageName)
                    }
                }
            }

            VisibilityMode.BLOCKLIST -> {
                // Hide only the apps in blockedApps, show everything else
                getAllLaunchablePackages().forEach { packageName ->
                    if (packageName in blockedApps && packageName != context.packageName) {
                        hideApp(packageName)
                    } else {
                        showApp(packageName)
                    }
                }
            }
        }
    }

    /**
     * Clear all visibility restrictions (show all apps).
     */
    fun clearVisibilityPolicy(): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Clearing policy requires Device Owner" }

        getHiddenApps().forEach { packageName ->
            showApp(packageName)
        }
    }

    // ============================================
    // Default Launcher Management
    // ============================================

    /**
     * Set an activity as the default launcher (home screen).
     *
     * This makes the specified activity the persistent default launcher,
     * overriding user preferences.
     *
     * @param launcherActivity The ComponentName of the launcher activity
     * @return Result indicating success or failure
     */
    fun setAsDefaultLauncher(launcherActivity: ComponentName): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Setting default launcher requires Device Owner" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { "Requires Android L+" }

        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        devicePolicyManager.addPersistentPreferredActivity(
            adminComponent,
            filter,
            launcherActivity
        )
    }

    /**
     * Clear the default launcher preference.
     *
     * This removes the persistent preferred activity and allows
     * the user to choose their launcher again.
     *
     * @return Result indicating success or failure
     */
    fun clearDefaultLauncher(): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Clearing default launcher requires Device Owner" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { "Requires Android L+" }

        devicePolicyManager.clearPackagePersistentPreferredActivities(
            adminComponent,
            context.packageName
        )
    }

    /**
     * Get the current default launcher package.
     *
     * @return The package name of the current default launcher, or null if none set
     */
    fun getDefaultLauncher(): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    // ============================================
    // App Queries
    // ============================================

    /**
     * Get all launchable apps with their details.
     *
     * Returns apps that have a launcher activity and are not hidden.
     *
     * @param includeHidden If true, also include hidden apps
     * @return List of LaunchableApp objects
     */
    fun getLaunchableApps(includeHidden: Boolean = false): List<LaunchableApp> {
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val activities = packageManager.queryIntentActivities(launchIntent, 0)

        return activities.mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName

            // Skip hidden apps if not including them
            if (!includeHidden && isAppHidden(packageName)) {
                return@mapNotNull null
            }

            val appInfo = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }

            LaunchableApp(
                packageName = packageName,
                label = resolveInfo.loadLabel(packageManager).toString(),
                icon = resolveInfo.loadIcon(packageManager),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isHidden = isAppHidden(packageName),
                isSuspended = isAppSuspended(packageName)
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Get launchable apps filtered by policy.
     *
     * @param allowedApps List of allowed package names (empty = all allowed)
     * @param blockedApps List of blocked package names
     * @return List of LaunchableApp objects that pass the filter
     */
    fun getFilteredApps(
        allowedApps: List<String>,
        blockedApps: List<String>
    ): List<LaunchableApp> {
        return getLaunchableApps(includeHidden = false).filter { app ->
            val isBlocked = app.packageName in blockedApps
            val isAllowed = allowedApps.isEmpty() || app.packageName in allowedApps

            !isBlocked && isAllowed
        }
    }

    // ============================================
    // Private Helpers
    // ============================================

    private fun getAllInstalledPackages(): List<String> {
        return packageManager.getInstalledApplications(0).map { it.packageName }
    }

    private fun getAllLaunchablePackages(): List<String> {
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return packageManager.queryIntentActivities(launchIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
    }

    private fun getSystemPackages(): Set<String> {
        return packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            .map { it.packageName }
            .toSet()
    }
}

/**
 * Visibility mode for app filtering.
 */
enum class VisibilityMode {
    /** No visibility restrictions - show all apps */
    DEFAULT,

    /** Only show apps in the allowed list */
    ALLOWLIST,

    /** Hide apps in the blocked list, show everything else */
    BLOCKLIST
}

/**
 * Information about a launchable app.
 */
data class LaunchableApp(
    /** Package name of the app */
    val packageName: String,

    /** Display label of the app */
    val label: String,

    /** App icon drawable */
    val icon: Drawable?,

    /** Whether this is a system app */
    val isSystemApp: Boolean,

    /** Whether the app is currently hidden */
    val isHidden: Boolean = false,

    /** Whether the app is currently suspended */
    val isSuspended: Boolean = false
)
