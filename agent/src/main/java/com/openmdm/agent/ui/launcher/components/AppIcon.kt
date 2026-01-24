package com.openmdm.agent.ui.launcher.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openmdm.agent.ui.launcher.model.AppType
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.agent.ui.theme.OpenMDMAgentTheme

/**
 * Individual app icon in the launcher grid.
 */
@Composable
fun AppIcon(
    app: LauncherAppInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Int = 48
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // App icon
        Box(
            modifier = Modifier.size(iconSize.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                app.icon != null -> {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier.size(iconSize.dp)
                    )
                }
                app.type == AppType.WEB -> {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = app.label,
                        modifier = Modifier.size(iconSize.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                app.type == AppType.INTENT -> {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = app.label,
                        modifier = Modifier.size(iconSize.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = app.label,
                        modifier = Modifier.size(iconSize.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // App label
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * App icon for the bottom bar (smaller size).
 */
@Composable
fun BottomBarAppIcon(
    app: LauncherAppInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppIcon(
        app = app,
        onClick = onClick,
        modifier = modifier,
        iconSize = 40
    )
}

// ============================================
// Preview Functions
// ============================================

@Preview(showBackground = true, name = "App Icon - Installed App")
@Composable
private fun AppIconInstalledPreview() {
    OpenMDMAgentTheme {
        AppIcon(
            app = LauncherAppInfo(
                packageName = "com.example.app",
                label = "Calculator",
                type = AppType.INSTALLED
            ),
            onClick = {}
        )
    }
}

@Preview(showBackground = true, name = "App Icon - Web App")
@Composable
private fun AppIconWebPreview() {
    OpenMDMAgentTheme {
        AppIcon(
            app = LauncherAppInfo(
                packageName = "web-google",
                label = "Google",
                type = AppType.WEB,
                url = "https://google.com"
            ),
            onClick = {}
        )
    }
}

@Preview(showBackground = true, name = "App Icon - Intent")
@Composable
private fun AppIconIntentPreview() {
    OpenMDMAgentTheme {
        AppIcon(
            app = LauncherAppInfo(
                packageName = "intent-settings",
                label = "Settings",
                type = AppType.INTENT,
                intentAction = "android.settings.SETTINGS"
            ),
            onClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Bottom Bar App Icon")
@Composable
private fun BottomBarAppIconPreview() {
    OpenMDMAgentTheme {
        BottomBarAppIcon(
            app = LauncherAppInfo(
                packageName = "com.example.app",
                label = "Phone",
                type = AppType.INSTALLED
            ),
            onClick = {}
        )
    }
}
