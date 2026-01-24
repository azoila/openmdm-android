package com.openmdm.agent.domain.usecase

import com.openmdm.agent.domain.repository.IAppRepository
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.library.policy.LauncherConfig
import javax.inject.Inject

/**
 * Use case for loading apps to display in the launcher.
 *
 * Handles:
 * 1. Loading all launchable apps from the system
 * 2. Filtering apps based on launcher configuration (allowlist/blocklist)
 * 3. Separating main apps from bottom bar apps
 * 4. Sorting apps by screen order
 */
class LoadLauncherAppsUseCase @Inject constructor(
    private val appRepository: IAppRepository
) {

    /**
     * Load and filter apps for the launcher.
     *
     * @param config Optional launcher configuration for filtering
     * @return Result containing the launcher apps split by location
     */
    suspend operator fun invoke(config: LauncherConfig? = null): Result<LauncherAppsResult> {
        return try {
            val apps = appRepository.getLaunchableApps(config)

            // Separate main apps from bottom bar apps
            val mainApps = apps.filter { !it.isBottomBar }
                .sortedBy { it.screenOrder ?: Int.MAX_VALUE }
            val bottomBarApps = apps.filter { it.isBottomBar }
                .sortedBy { it.screenOrder ?: Int.MAX_VALUE }

            Result.success(
                LauncherAppsResult(
                    mainApps = mainApps,
                    bottomBarApps = bottomBarApps
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Result containing launcher apps split by location.
 *
 * @property mainApps Apps to display in the main grid
 * @property bottomBarApps Apps to display in the bottom bar
 */
data class LauncherAppsResult(
    val mainApps: List<LauncherAppInfo>,
    val bottomBarApps: List<LauncherAppInfo>
)
