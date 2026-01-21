package com.openmdm.agent.domain.repository

import androidx.compose.ui.graphics.ImageBitmap
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.library.policy.LauncherConfig

/**
 * Repository interface for app-related operations.
 *
 * Handles loading and filtering apps for the launcher display.
 */
interface IAppRepository {

    /**
     * Get all launchable apps from the system, filtered by the given configuration.
     *
     * @param config Optional launcher configuration for filtering apps
     * @return List of apps to display in the launcher
     */
    suspend fun getLaunchableApps(config: LauncherConfig? = null): List<LauncherAppInfo>

    /**
     * Get the app icon as an ImageBitmap for a specific package.
     *
     * @param packageName The package name of the app
     * @return The app icon, or null if not found
     */
    suspend fun getAppIcon(packageName: String): ImageBitmap?

    /**
     * Check if a package is installed on the device.
     *
     * @param packageName The package name to check
     * @return true if installed, false otherwise
     */
    suspend fun isPackageInstalled(packageName: String): Boolean
}
