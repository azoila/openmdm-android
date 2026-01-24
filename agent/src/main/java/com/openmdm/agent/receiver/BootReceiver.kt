package com.openmdm.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.service.MDMService
import com.openmdm.agent.util.DeviceOwnerManager
import com.openmdm.library.policy.KioskConfig
import com.openmdm.library.policy.PolicyMapper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Boot Receiver to start MDM service on device boot and restore kiosk mode.
 *
 * Handles:
 * - BOOT_COMPLETED: Device finished booting
 * - QUICKBOOT_POWERON: Quick boot completed (some devices)
 * - MY_PACKAGE_REPLACED: Agent was updated
 *
 * On boot, this receiver:
 * 1. Starts the MDM service for heartbeat and policy sync
 * 2. Checks if kiosk mode was enabled and restores it
 * 3. Launches the kiosk main app if configured
 * 4. Smart fallback: if mainApp isn't installed but a variant from allowedApps is,
 *    launches that variant instead (e.g., staging or dev variants)
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    @Inject
    lateinit var mdmRepository: MDMRepository

    @Inject
    lateinit var deviceOwnerManager: DeviceOwnerManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Device boot completed, starting MDM service and restoring kiosk mode")
                startMdmService(context)
                restoreKioskMode(context)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Agent package replaced, restoring state and starting service")

                // Restore agent state from backup (if exists)
                try {
                    val restored = deviceOwnerManager.restoreAgentState()
                    if (restored) {
                        Log.i(TAG, "Agent state restored after update")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore agent state", e)
                }

                // Start MDM service with post-update flag
                startMdmService(context, isPostUpdate = true)
                // Also restore kiosk mode after update
                restoreKioskMode(context)
            }
        }
    }

    private fun startMdmService(context: Context, isPostUpdate: Boolean = false) {
        val serviceIntent = Intent(context, MDMService::class.java).apply {
            action = MDMService.ACTION_START
            if (isPostUpdate) {
                putExtra("isPostUpdate", true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun restoreKioskMode(context: Context) {
        scope.launch {
            try {
                // Small delay to ensure MDM service is started and Hilt injection is ready
                kotlinx.coroutines.delay(2000)

                // Check if device is enrolled
                val state = mdmRepository.enrollmentState.first()
                if (!state.isEnrolled) {
                    Log.d(TAG, "Device not enrolled, skipping kiosk restoration")
                    return@launch
                }

                // Get policy settings
                val policyJson = mdmRepository.getPolicySettingsJson()
                if (policyJson == null) {
                    Log.d(TAG, "No policy settings found, skipping kiosk restoration")
                    return@launch
                }

                // Parse policy settings
                val settings = try {
                    PolicyMapper.fromJson(policyJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse policy settings", e)
                    return@launch
                }

                // Check if kiosk mode is enabled
                if (!settings.kioskMode) {
                    Log.d(TAG, "Kiosk mode not enabled in policy")
                    return@launch
                }

                val mainApp = settings.mainApp
                if (mainApp == null) {
                    Log.d(TAG, "No main app configured in kiosk policy")
                    return@launch
                }

                // Smart fallback: find the app to launch
                // 1. First try the configured mainApp
                // 2. If not installed, look for installed variants in allowedApps
                val appToLaunch = findAppToLaunch(context, mainApp, settings.allowedApps)
                if (appToLaunch == null) {
                    Log.w(TAG, "Kiosk main app $mainApp and no variants found in allowedApps, skipping kiosk restoration")
                    return@launch
                }

                if (appToLaunch != mainApp) {
                    Log.i(TAG, "Using variant $appToLaunch instead of mainApp $mainApp")
                }

                Log.i(TAG, "Restoring kiosk mode with app: $appToLaunch")

                // Enter kiosk mode and launch the app
                // Override mainApp in kiosk config with the actual app to launch
                if (deviceOwnerManager.isDeviceOwner()) {
                    val kioskConfig = KioskConfig.fromPolicySettings(settings).copy(mainApp = appToLaunch)
                    val result = deviceOwnerManager.getKioskManager().enterKioskMode(kioskConfig)
                    if (result.isSuccess) {
                        Log.i(TAG, "Kiosk mode restored successfully with app: $appToLaunch")
                    } else {
                        Log.e(TAG, "Failed to restore kiosk mode: ${result.exceptionOrNull()?.message}")
                        // Fallback: just launch the app
                        launchApp(context, appToLaunch)
                    }
                } else {
                    Log.w(TAG, "Not device owner, launching app without lock task mode")
                    launchApp(context, appToLaunch)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring kiosk mode", e)
            }
        }
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Find the app to launch for kiosk mode.
     * 1. First try the configured mainApp
     * 2. If not installed, look for installed variants in allowedApps
     */
    private fun findAppToLaunch(context: Context, mainApp: String, allowedApps: List<String>): String? {
        // First check if mainApp is installed
        if (isAppInstalled(context, mainApp)) {
            return mainApp
        }

        // Smart fallback: look for installed variants in allowedApps
        // Prioritize variants of mainApp (e.g., mainApp.staging, mainApp.dev)
        val installedVariant = allowedApps.find { pkg ->
            isAppInstalled(context, pkg) && isAppVariant(pkg, mainApp)
        }

        if (installedVariant != null) {
            Log.i(TAG, "Found installed variant: $installedVariant (mainApp: $mainApp)")
            return installedVariant
        }

        return null
    }

    /**
     * Check if a package is a variant of another (e.g., staging or dev build).
     * Example: com.example.app.staging is a variant of com.example.app
     */
    private fun isAppVariant(packageName: String, mainApp: String): Boolean {
        return packageName.startsWith("$mainApp.")
    }

    private fun launchApp(context: Context, packageName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                context.startActivity(launchIntent)
                Log.i(TAG, "App launched: $packageName")
            } else {
                Log.e(TAG, "Could not find launch intent for: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
        }
    }
}
