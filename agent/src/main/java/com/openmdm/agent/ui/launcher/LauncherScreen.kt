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
import com.openmdm.agent.ui.launcher.model.LauncherEvent

/**
 * Main launcher screen composable.
 */
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel = hiltViewModel(),
    onAdminPanelRequested: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    onAppClick = viewModel::onAppClick,
                    columns = uiState.columns,
                    isLoading = uiState.isLoading,
                    modifier = Modifier.weight(1f)
                )

                // Bottom bar with pinned apps
                if (uiState.showBottomBar && uiState.bottomBarApps.isNotEmpty()) {
                    BottomBar(
                        apps = uiState.bottomBarApps,
                        onAppClick = viewModel::onAppClick
                    )
                }
            }

            // Blocked app overlay
            uiState.blockedAppPackage?.let { packageName ->
                BlockedAppOverlay(
                    packageName = packageName,
                    onDismiss = viewModel::dismissBlockedOverlay,
                    onAdminClick = viewModel::openAdminPanel
                )
            }
        }
    }
}
