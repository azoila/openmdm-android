package com.openmdm.agent.ui.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.ui.launcher.model.AppType
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.agent.ui.launcher.model.LauncherEvent
import com.openmdm.agent.ui.launcher.model.LauncherUiState
import com.openmdm.agent.util.DeviceOwnerManager
import com.openmdm.library.device.LauncherManager
import com.openmdm.library.policy.LauncherAppType
import com.openmdm.library.policy.LauncherConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the MDM Launcher screen.
 *
 * Manages app loading, filtering based on policy, and app launching.
 */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mdmRepository: MDMRepository,
    private val deviceOwnerManager: DeviceOwnerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LauncherEvent>()
    val events = _events.asSharedFlow()

    private val packageManager: PackageManager = context.packageManager

    private var launcherConfig: LauncherConfig? = null

    init {
        loadApps()
    }

    /**
     * Load and filter apps based on policy.
     */
    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                val apps = withContext(Dispatchers.IO) {
                    loadAppsFromSystem()
                }

                // Separate main apps from bottom bar apps
                val mainApps = apps.filter { !it.isBottomBar }
                    .sortedBy { it.screenOrder ?: Int.MAX_VALUE }
                val bottomBarApps = apps.filter { it.isBottomBar }
                    .sortedBy { it.screenOrder ?: Int.MAX_VALUE }

                _uiState.update {
                    it.copy(
                        apps = mainApps,
                        bottomBarApps = bottomBarApps,
                        isLoading = false,
                        columns = launcherConfig?.columns ?: 4,
                        showBottomBar = launcherConfig?.showBottomBar ?: true
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Failed to load apps"
                    )
                }
            }
        }
    }

    /**
     * Update the launcher configuration from policy.
     */
    fun updateConfig(config: LauncherConfig) {
        launcherConfig = config
        _uiState.update {
            it.copy(
                isKioskMode = config.mode == "allowlist",
                columns = config.columns,
                showBottomBar = config.showBottomBar
            )
        }
        loadApps()
    }

    /**
     * Handle app click - launch the app or show blocked overlay.
     */
    fun onAppClick(app: LauncherAppInfo) {
        viewModelScope.launch {
            when (app.type) {
                AppType.INSTALLED -> launchApp(app.packageName)
                AppType.WEB -> launchWebUrl(app.url)
                AppType.INTENT -> launchIntent(app.intentAction, app.intentUri)
            }
        }
    }

    /**
     * Show blocked app overlay.
     */
    fun showBlockedOverlay(packageName: String) {
        _uiState.update { it.copy(blockedAppPackage = packageName) }
    }

    /**
     * Dismiss blocked app overlay.
     */
    fun dismissBlockedOverlay() {
        _uiState.update { it.copy(blockedAppPackage = null) }
        viewModelScope.launch {
            _events.emit(LauncherEvent.BlockedOverlayDismissed)
        }
    }

    /**
     * Open the admin panel (MainActivity).
     */
    fun openAdminPanel() {
        viewModelScope.launch {
            _events.emit(LauncherEvent.AdminPanelRequested)
        }
        dismissBlockedOverlay()
    }

    /**
     * Check if an app is allowed to launch.
     */
    fun isAppAllowed(packageName: String): Boolean {
        val config = launcherConfig ?: return true

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

    // ============================================
    // Private Methods
    // ============================================

    private suspend fun loadAppsFromSystem(): List<LauncherAppInfo> {
        val config = launcherConfig
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
            val icon = drawableToImageBitmap(drawable)

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

    private fun launchApp(packageName: String) {
        try {
            if (!isAppAllowed(packageName)) {
                showBlockedOverlay(packageName)
                return
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Failed to launch app: ${e.message}") }
        }
    }

    private fun launchWebUrl(url: String?) {
        if (url.isNullOrBlank()) return

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Failed to open URL: ${e.message}") }
        }
    }

    private fun launchIntent(action: String?, uri: String?) {
        if (action.isNullOrBlank()) return

        try {
            val intent = if (uri != null) {
                Intent(action, Uri.parse(uri))
            } else {
                Intent(action)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Failed to launch intent: ${e.message}") }
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable?): ImageBitmap? {
        if (drawable == null) return null

        return try {
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
