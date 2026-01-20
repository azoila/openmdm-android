package com.openmdm.agent.ui.launcher.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo

/**
 * Bottom bar with pinned apps.
 */
@Composable
fun BottomBar(
    apps: List<LauncherAppInfo>,
    onAppClick: (LauncherAppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    if (apps.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            apps.forEach { app ->
                BottomBarAppIcon(
                    app = app,
                    onClick = { onAppClick(app) }
                )
            }
        }
    }
}
