package com.openmdm.agent.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.openmdm.agent.service.MDMService

/**
 * Broadcast receiver for APK installation and uninstallation results.
 *
 * This receiver handles callbacks from PackageInstaller for silent
 * app installation/uninstallation when Device Owner mode is active.
 *
 * On success, it notifies MDMService to:
 * 1. Report the result to the MDM server
 * 2. Apply post-install setup (permissions, battery whitelist)
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OpenMDM.InstallResult"
        const val ACTION_INSTALL_RESULT = "com.openmdm.agent.INSTALL_RESULT"
        const val ACTION_UNINSTALL_RESULT = "com.openmdm.agent.UNINSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (intent.action) {
            ACTION_INSTALL_RESULT -> handleInstallResult(context, packageName, status, message)
            ACTION_UNINSTALL_RESULT -> handleUninstallResult(context, packageName, status, message)
        }
    }

    private fun handleInstallResult(context: Context, packageName: String?, status: Int, message: String?) {
        val success = status == PackageInstaller.STATUS_SUCCESS
        val statusMessage = getInstallStatusMessage(status, message)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package $packageName installed successfully")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Package $packageName requires user action")
            }
            else -> {
                Log.e(TAG, "Package $packageName installation failed: $statusMessage")
            }
        }

        // Notify MDMService to report result and handle post-install setup
        if (packageName != null) {
            notifyMDMService(
                context,
                MDMService.ACTION_INSTALL_COMPLETE,
                packageName,
                success,
                statusMessage
            )
        }
    }

    private fun handleUninstallResult(context: Context, packageName: String?, status: Int, message: String?) {
        val success = status == PackageInstaller.STATUS_SUCCESS
        val statusMessage = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Uninstalled successfully"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Requires user action"
            else -> message ?: "Unknown error (status: $status)"
        }

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package $packageName uninstalled successfully")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Package $packageName uninstall requires user action")
            }
            else -> {
                Log.e(TAG, "Package $packageName uninstall failed with status $status: $message")
            }
        }

        // Notify MDMService to report result
        if (packageName != null) {
            notifyMDMService(
                context,
                MDMService.ACTION_UNINSTALL_COMPLETE,
                packageName,
                success,
                statusMessage
            )
        }
    }

    private fun notifyMDMService(
        context: Context,
        action: String,
        packageName: String,
        success: Boolean,
        message: String
    ) {
        val serviceIntent = Intent(context, MDMService::class.java).apply {
            this.action = action
            putExtra(MDMService.EXTRA_PACKAGE_NAME, packageName)
            putExtra(MDMService.EXTRA_SUCCESS, success)
            putExtra(MDMService.EXTRA_MESSAGE, message)
        }
        context.startService(serviceIntent)
    }

    private fun getInstallStatusMessage(status: Int, message: String?): String {
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Installed successfully"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Requires user action"
            PackageInstaller.STATUS_FAILURE -> message ?: "Installation failed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation aborted"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Package conflict"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Package incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid package"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Insufficient storage"
            else -> message ?: "Unknown error (status: $status)"
        }
    }
}
