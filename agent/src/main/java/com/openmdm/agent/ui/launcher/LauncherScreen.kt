package com.openmdm.agent.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmdm.agent.ui.launcher.components.AppGrid
import com.openmdm.agent.ui.launcher.components.BlockedAppOverlay
import com.openmdm.agent.ui.launcher.components.BottomBar
import com.openmdm.agent.ui.launcher.components.EnrollmentScreen
import com.openmdm.agent.ui.launcher.components.LoadingScreen
import com.openmdm.agent.ui.launcher.model.LauncherEvent
import com.openmdm.agent.ui.launcher.model.LauncherScreenState
import com.openmdm.agent.ui.launcher.model.LauncherUiState

/**
 * Main launcher screen composable.
 * Implements enrollment-first flow: shows enrollment screen until device is enrolled.
 */
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel = hiltViewModel(),
    onAdminPanelRequested: () -> Unit = {},
    onScanQrCode: () -> Unit = {}
) {
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LauncherEvent.AdminPanelRequested -> onAdminPanelRequested()
                is LauncherEvent.BlockedOverlayDismissed -> { /* handled by UI state */ }
                is LauncherEvent.RefreshRequested -> viewModel.loadApps()
                is LauncherEvent.AppClicked -> { /* handled in ViewModel */ }
            }
        }
    }

    when (val state = screenState) {
        is LauncherScreenState.Loading -> {
            LoadingScreen(message = "Loading...")
        }
        is LauncherScreenState.Enrollment -> {
            EnrollmentScreen(
                serverUrl = state.serverUrl,
                errorMessage = state.errorMessage,
                isEnrolling = state.isEnrolling,
                onEnroll = viewModel::enroll,
                onScanQrCode = onScanQrCode
            )
        }
        is LauncherScreenState.Launcher -> {
            LauncherContent(
                uiState = state.uiState,
                onAppClick = viewModel::onAppClick,
                onDismissBlocked = viewModel::dismissBlockedOverlay,
                onAdminClick = viewModel::openAdminPanel
            )
        }
    }
}

/**
 * Main launcher content showing app grid and bottom bar.
 */
@Composable
private fun LauncherContent(
    uiState: LauncherUiState,
    onAppClick: (com.openmdm.agent.ui.launcher.model.LauncherAppInfo) -> Unit,
    onDismissBlocked: () -> Unit,
    onAdminClick: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error messages
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Main app grid
                AppGrid(
                    apps = uiState.apps,
                    onAppClick = onAppClick,
                    columns = uiState.columns,
                    isLoading = uiState.isLoading,
                    modifier = Modifier.weight(1f)
                )

                // Bottom bar with pinned apps
                if (uiState.showBottomBar && uiState.bottomBarApps.isNotEmpty()) {
                    BottomBar(
                        apps = uiState.bottomBarApps,
                        onAppClick = onAppClick
                    )
                }
            }

            // Blocked app overlay
            uiState.blockedAppPackage?.let { packageName ->
                BlockedAppOverlay(
                    packageName = packageName,
                    onDismiss = onDismissBlocked,
                    onAdminClick = onAdminClick
                )
            }
        }
    }
}
