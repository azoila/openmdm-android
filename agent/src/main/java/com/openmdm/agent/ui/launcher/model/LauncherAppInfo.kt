package com.openmdm.agent.ui.launcher.model

import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Represents an app to be displayed in the launcher.
 */
data class LauncherAppInfo(
    /** Package name of the app */
    val packageName: String,

    /** Display label of the app */
    val label: String,

    /** App icon as a Compose ImageBitmap */
    val icon: ImageBitmap? = null,

    /** Original Android drawable icon */
    val iconDrawable: Drawable? = null,

    /** Sort order on the screen */
    val screenOrder: Int? = null,

    /** Whether to show in the bottom bar */
    val isBottomBar: Boolean = false,

    /** Type of app entry */
    val type: AppType = AppType.INSTALLED,

    /** URL for web links */
    val url: String? = null,

    /** Intent action for custom intents */
    val intentAction: String? = null,

    /** Intent URI for custom intents */
    val intentUri: String? = null,

    /** Whether this is a system app */
    val isSystemApp: Boolean = false
)

/**
 * Type of launcher app entry.
 */
enum class AppType {
    /** Installed Android app */
    INSTALLED,

    /** Web link (opens in browser) */
    WEB,

    /** Custom intent (e.g., tel:, mailto:) */
    INTENT
}

/**
 * State of the launcher screen.
 */
data class LauncherUiState(
    /** Apps to display in the main grid */
    val apps: List<LauncherAppInfo> = emptyList(),

    /** Apps to display in the bottom bar */
    val bottomBarApps: List<LauncherAppInfo> = emptyList(),

    /** Package name of currently blocked app (for overlay) */
    val blockedAppPackage: String? = null,

    /** Whether launcher is in kiosk mode */
    val isKioskMode: Boolean = false,

    /** Whether the launcher is loading */
    val isLoading: Boolean = true,

    /** Number of columns in the grid */
    val columns: Int = 4,

    /** Whether to show the bottom bar */
    val showBottomBar: Boolean = true,

    /** Error message if any */
    val errorMessage: String? = null
)

/**
 * Events that can be triggered from the launcher.
 */
sealed class LauncherEvent {
    /** App was clicked */
    data class AppClicked(val app: LauncherAppInfo) : LauncherEvent()

    /** Blocked app overlay dismissed */
    data object BlockedOverlayDismissed : LauncherEvent()

    /** Admin panel requested */
    data object AdminPanelRequested : LauncherEvent()

    /** Refresh apps requested */
    data object RefreshRequested : LauncherEvent()
}
