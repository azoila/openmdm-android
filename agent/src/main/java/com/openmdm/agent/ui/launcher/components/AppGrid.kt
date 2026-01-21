package com.openmdm.agent.ui.launcher.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openmdm.agent.R
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.agent.ui.theme.OpenMDMAgentTheme

/**
 * Grid of app icons for the launcher.
 */
@Composable
fun AppGrid(
    apps: List<LauncherAppInfo>,
    onAppClick: (LauncherAppInfo) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    isLoading: Boolean = false
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator()
            }
            apps.isEmpty() -> {
                Text(
                    text = stringResource(R.string.launcher_no_apps),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(
                        top = 16.dp,
                        bottom = 16.dp,
                        start = 8.dp,
                        end = 8.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = apps,
                        key = { index, app -> "${app.packageName}_$index" }
                    ) { _, app ->
                        AppIcon(
                            app = app,
                            onClick = { onAppClick(app) }
                        )
                    }
                }
            }
        }
    }
}

// ============================================
// Preview Functions
// ============================================

@Preview(showBackground = true, name = "App Grid - With Apps")
@Composable
private fun AppGridWithAppsPreview() {
    val sampleApps = listOf(
        LauncherAppInfo(packageName = "com.example.app1", label = "Calculator"),
        LauncherAppInfo(packageName = "com.example.app2", label = "Calendar"),
        LauncherAppInfo(packageName = "com.example.app3", label = "Camera"),
        LauncherAppInfo(packageName = "com.example.app4", label = "Clock"),
        LauncherAppInfo(packageName = "com.example.app5", label = "Contacts"),
        LauncherAppInfo(packageName = "com.example.app6", label = "Files")
    )
    OpenMDMAgentTheme {
        AppGrid(
            apps = sampleApps,
            onAppClick = {},
            columns = 4
        )
    }
}

@Preview(showBackground = true, name = "App Grid - Empty")
@Composable
private fun AppGridEmptyPreview() {
    OpenMDMAgentTheme {
        AppGrid(
            apps = emptyList(),
            onAppClick = {},
            columns = 4
        )
    }
}

@Preview(showBackground = true, name = "App Grid - Loading")
@Composable
private fun AppGridLoadingPreview() {
    OpenMDMAgentTheme {
        AppGrid(
            apps = emptyList(),
            onAppClick = {},
            columns = 4,
            isLoading = true
        )
    }
}
