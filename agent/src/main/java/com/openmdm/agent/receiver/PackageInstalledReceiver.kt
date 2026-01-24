package com.openmdm.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.openmdm.agent.data.MDMRepository
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
 * Receiver for package installation events.
 *
 * Handles auto-launching the kiosk main app after installation:
 * - Listens for PACKAGE_ADDED broadcasts
 * - Checks if the installed package is the configured mainApp in kiosk policy
 * - Uses smart fallback: if mainApp isn't installed but a variant from allowedApps is,
 *   auto-launch that variant (e.g., staging or dev variants)
 * - Automatically launches the app if kiosk mode is enabled
 *
 * This ensures seamless kiosk mode experience where the managed app
 * is automatically launched right after installation.
 */
@AndroidEntryPoint
class PackageInstalledReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageInstalledReceiver"
    }

    @Inject
    lateinit var mdmRepository: MDMRepository

    @Inject
    lateinit var deviceOwnerManager: DeviceOwnerManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return

        // Get the package name that was installed
        val packageName = intent.data?.schemeSpecificPart ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        Log.i(TAG, "Package installed: $packageName (replacing: $isReplacing)")

        // Check if this package should be auto-launched
        scope.launch {
            try {
                checkAndLaunchKioskApp(context, packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking/launching kiosk app", e)
            }
        }
    }

    private suspend fun checkAndLaunchKioskApp(context: Context, installedPackage: String) {
        // Check if device is enrolled
        val state = mdmRepository.enrollmentState.first()
        if (!state.isEnrolled) {
            Log.d(TAG, "Device not enrolled, skipping kiosk check")
            return
        }

        // Get policy settings
        val policyJson = mdmRepository.getPolicySettingsJson()
        if (policyJson == null) {
            Log.d(TAG, "No policy settings found, skipping kiosk check")
            return
        }

        // Parse policy settings
        val settings = try {
            PolicyMapper.fromJson(policyJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse policy settings", e)
            return
        }

        // Check if kiosk mode is enabled and this is the main app
        if (!settings.kioskMode) {
            Log.d(TAG, "Kiosk mode not enabled")
            return
        }

        val mainApp = settings.mainApp
        if (mainApp == null) {
            Log.d(TAG, "No main app configured in kiosk policy")
            return
        }

        // Smart fallback: check if installed package is mainApp or a variant in allowedApps
        val isMainApp = installedPackage == mainApp
        val isAllowedVariant = settings.allowedApps.contains(installedPackage) &&
                               isAppVariant(installedPackage, mainApp)

        if (!isMainApp && !isAllowedVariant) {
            Log.d(TAG, "Installed package $installedPackage is not the kiosk main app ($mainApp) or an allowed variant")
            return
        }

        // Determine which app to actually launch
        val appToLaunch = if (isMainApp) mainApp else installedPackage
        Log.i(TAG, "Kiosk app installed: $installedPackage (mainApp: $mainApp, launching: $appToLaunch)")

        // Small delay to ensure the app is fully installed and ready
        kotlinx.coroutines.delay(1000)

        // Enter kiosk mode and launch the app
        // Use the installed package as the mainApp for kiosk mode
        try {
            val kioskConfig = KioskConfig.fromPolicySettings(settings).copy(mainApp = appToLaunch)

            // If we're device owner, enter proper lock task mode
            if (deviceOwnerManager.isDeviceOwner()) {
                val result = deviceOwnerManager.getKioskManager().enterKioskMode(kioskConfig)
                if (result.isSuccess) {
                    Log.i(TAG, "Kiosk mode entered and app launched: $appToLaunch")
                } else {
                    Log.e(TAG, "Failed to enter kiosk mode: ${result.exceptionOrNull()?.message}")
                    // Fallback: just launch the app normally
                    launchApp(context, appToLaunch)
                }
            } else {
                // Not device owner, just launch the app
                Log.w(TAG, "Not device owner, launching app without lock task mode")
                launchApp(context, appToLaunch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter kiosk mode, launching app normally", e)
            launchApp(context, appToLaunch)
        }
    }

    /**
     * Check if a package is a variant of another (e.g., staging or dev build).
     * Example: com.example.app.staging is a variant of com.example.app
     */
    private fun isAppVariant(installedPackage: String, mainApp: String): Boolean {
        // Check if installedPackage starts with mainApp followed by a dot and suffix
        // e.g., "com.example.app.staging" starts with "com.example.app."
        return installedPackage.startsWith("$mainApp.")
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
