package com.openmdm.agent.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import com.openmdm.agent.domain.repository.IAppRepository
import com.openmdm.agent.ui.launcher.model.AppType
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.agent.util.DrawableConverter
import com.openmdm.library.policy.LauncherAppType
import com.openmdm.library.policy.LauncherConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [IAppRepository] for loading apps from the system.
 *
 * Handles:
 * - Querying launchable apps from PackageManager
 * - Filtering apps based on launcher configuration
 * - Converting app icons to ImageBitmap
 */
@Singleton
class AppRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drawableConverter: DrawableConverter
) : IAppRepository {

    private val packageManager: PackageManager = context.packageManager

    override suspend fun getLaunchableApps(config: LauncherConfig?): List<LauncherAppInfo> {
        val allowedApps = config?.allowedApps ?: emptyList()
        val blockedApps = config?.blockedApps ?: emptyList()
        val configuredApps = config?.apps ?: emptyList()
        val mode = config?.mode ?: "default"

        // Get all launchable apps
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = packageManager.queryIntentActivities(launchIntent, 0)

        // Build configured apps map for quick lookup
        val configuredAppsMap = configuredApps.associateBy { it.packageName }

        // Filter and map apps
        return activities.mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName

            // Apply visibility filtering based on mode
            val shouldInclude = when (mode) {
                "allowlist" -> {
                    packageName in allowedApps ||
                    packageName == context.packageName ||
                    configuredAppsMap.containsKey(packageName)
                }
                "blocklist" -> packageName !in blockedApps
                else -> true
            }

            if (!shouldInclude) return@mapNotNull null

            // Check if there's custom config for this app
            val appConfig = configuredAppsMap[packageName]

            val label = appConfig?.label
                ?: resolveInfo.loadLabel(packageManager).toString()

            val drawable = resolveInfo.loadIcon(packageManager)
            val icon = drawableConverter.toImageBitmap(drawable)

            LauncherAppInfo(
                packageName = packageName,
                label = label,
                icon = icon,
                iconDrawable = drawable,
                screenOrder = appConfig?.screenOrder,
                isBottomBar = appConfig?.isBottomBar ?: false,
                type = when (appConfig?.type) {
                    LauncherAppType.WEB -> AppType.WEB
                    LauncherAppType.INTENT -> AppType.INTENT
                    else -> AppType.INSTALLED
                },
                url = appConfig?.url,
                intentAction = appConfig?.intentAction,
                intentUri = appConfig?.intentUri,
                isSystemApp = (resolveInfo.activityInfo.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.let { apps ->
            // Add web and intent entries from config that aren't installed apps
            val installedPackages = apps.map { it.packageName }.toSet()
            val additionalApps = configuredApps.filter {
                it.type != LauncherAppType.APP && it.packageName !in installedPackages
            }.map { appConfig ->
                LauncherAppInfo(
                    packageName = appConfig.packageName,
                    label = appConfig.label ?: appConfig.packageName,
                    icon = null,
                    screenOrder = appConfig.screenOrder,
                    isBottomBar = appConfig.isBottomBar,
                    type = when (appConfig.type) {
                        LauncherAppType.WEB -> AppType.WEB
                        LauncherAppType.INTENT -> AppType.INTENT
                        else -> AppType.INSTALLED
                    },
                    url = appConfig.url,
                    intentAction = appConfig.intentAction,
                    intentUri = appConfig.intentUri
                )
            }
            apps + additionalApps
        }
    }

    override suspend fun getAppIcon(packageName: String): ImageBitmap? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val drawable = packageManager.getApplicationIcon(appInfo)
            drawableConverter.toImageBitmap(drawable)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    override suspend fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
