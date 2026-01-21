package com.openmdm.agent.ui.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.service.MDMService
import com.openmdm.agent.ui.MainActivity
import com.openmdm.agent.ui.theme.OpenMDMAgentTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MDM Launcher Activity
 *
 * This activity serves as a custom home screen / launcher for managed devices.
 * It implements an enrollment-first flow: device must be enrolled before showing
 * the launcher content. This ensures devices cannot bypass MDM enrollment.
 *
 * Features:
 * - Enrollment-first: Shows enrollment screen until device is enrolled
 * - Displays filtered app grid based on policy
 * - Blocks launching of unauthorized apps
 * - Supports kiosk mode integration
 * - Can be set as persistent default launcher
 * - Starts MDM service after enrollment
 */
@AndroidEntryPoint
class LauncherActivity : ComponentActivity() {

    @Inject
    lateinit var mdmRepository: MDMRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        enableEdgeToEdge()

        setContent {
            OpenMDMAgentTheme {
                LauncherScreen(
                    onAdminPanelRequested = ::openAdminPanel,
                    onScanQrCode = ::openQrScanner
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Start MDM service if enrolled
        lifecycleScope.launch {
            val state = mdmRepository.enrollmentState.first()
            if (state.isEnrolled) {
                startMDMService()
            }
        }
    }

    /**
     * Block the back button to prevent exiting the launcher.
'     * This is intentional for MDM launcher functionality.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - prevent users from exiting the launcher
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
     * Open QR code scanner for enrollment.
     * TODO: Implement QR code scanning
     */
    private fun openQrScanner() {
        Toast.makeText(this, "QR Code scanning coming soon", Toast.LENGTH_SHORT).show()
    }

    /**
     * Start the MDM service for heartbeat and policy updates.
     */
    private fun startMDMService() {
        val intent = Intent(this, MDMService::class.java).apply {
            action = MDMService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Ensure launcher stays in foreground when home button is pressed.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Activity is already running, no action needed
    }
}
