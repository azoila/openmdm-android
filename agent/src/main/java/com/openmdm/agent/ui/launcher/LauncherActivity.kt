package com.openmdm.agent.ui.launcher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.openmdm.agent.ui.MainActivity
import com.openmdm.agent.ui.theme.OpenMDMAgentTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * MDM Launcher Activity
 *
 * This activity serves as a custom home screen / launcher for managed devices.
 * It displays only the apps allowed by the MDM policy and can be set as the
 * default launcher when Device Owner permissions are granted.
 *
 * Features:
 * - Displays filtered app grid based on policy
 * - Blocks launching of unauthorized apps
 * - Supports kiosk mode integration
 * - Can be set as persistent default launcher
 */
@AndroidEntryPoint
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        enableEdgeToEdge()

        setContent {
            OpenMDMAgentTheme {
                LauncherScreen(
                    onAdminPanelRequested = ::openAdminPanel
                )
            }
        }
    }

    /**
     * Block the back button to prevent exiting the launcher.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - prevent users from exiting the launcher
        // This is intentional for MDM launcher functionality
    }

    /**
     * Handle hardware key events for app shortcuts.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Future: Could add hardware key shortcuts here
        // For now, use default behavior
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Open the MDM admin panel (MainActivity).
     */
    private fun openAdminPanel() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * Ensure launcher stays in foreground when home button is pressed.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Activity is already running, no action needed
    }
}
