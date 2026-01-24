package com.openmdm.agent.domain.usecase

import android.content.Context
import com.openmdm.agent.ui.launcher.model.AppType
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.agent.util.AppLauncher
import com.openmdm.library.policy.LauncherConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Use case for launching apps from the launcher.
 *
 * Handles:
 * 1. Checking if app is allowed by policy
 * 2. Launching installed apps, web URLs, and custom intents
 * 3. Returning appropriate result for blocked apps
 */
class LaunchAppUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLauncher: AppLauncher
) {

    /**
     * Attempt to launch an app.
     *
     * @param app The app to launch
     * @param config Current launcher configuration for policy checks
     * @return Result indicating success, blocked, or failure
     */
    operator fun invoke(app: LauncherAppInfo, config: LauncherConfig?): LaunchResult {
        // Check if app is allowed
        if (!isAppAllowed(app.packageName, config)) {
            return LaunchResult.Blocked(app.packageName)
        }

        return try {
            val success = when (app.type) {
                AppType.INSTALLED -> appLauncher.launchApp(app.packageName)
                AppType.WEB -> appLauncher.launchWebUrl(app.url)
                AppType.INTENT -> appLauncher.launchIntent(app.intentAction, app.intentUri)
            }

            if (success) {
                LaunchResult.Success
            } else {
                LaunchResult.Failed("Failed to launch app")
            }
        } catch (e: Exception) {
            LaunchResult.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * Check if an app is allowed to launch based on policy.
     *
     * @param packageName The package name to check
     * @param config Current launcher configuration
     * @return true if app is allowed, false if blocked
     */
    fun isAppAllowed(packageName: String, config: LauncherConfig?): Boolean {
        if (config == null) return true

        return when (config.mode) {
            "allowlist" -> {
                packageName in config.allowedApps ||
                packageName == context.packageName ||
                packageName == "com.android.settings"
            }
            "blocklist" -> {
                packageName !in config.blockedApps
            }
            else -> true
        }
    }
}

/**
 * Result of attempting to launch an app.
 */
sealed class LaunchResult {
    /** App launched successfully */
    data object Success : LaunchResult()

    /** App is blocked by policy */
    data class Blocked(val packageName: String) : LaunchResult()

    /** App launch failed with an error */
    data class Failed(val errorMessage: String) : LaunchResult()
}
