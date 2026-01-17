package com.openmdm.agent.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Broadcast receiver for APK installation and uninstallation results.
 *
 * This receiver handles callbacks from PackageInstaller for silent
 * app installation/uninstallation when Device Owner mode is active.
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
            ACTION_INSTALL_RESULT -> handleInstallResult(packageName, status, message)
            ACTION_UNINSTALL_RESULT -> handleUninstallResult(packageName, status, message)
        }
    }

    private fun handleInstallResult(packageName: String?, status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package $packageName installed successfully")
                // TODO: Send success event to MDM server
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Package $packageName requires user action")
                // This shouldn't happen with Device Owner, but handle it
            }
            PackageInstaller.STATUS_FAILURE -> {
                Log.e(TAG, "Package $packageName installation failed: $message")
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.e(TAG, "Package $packageName installation aborted: $message")
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.e(TAG, "Package $packageName installation blocked: $message")
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.e(TAG, "Package $packageName installation conflict: $message")
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.e(TAG, "Package $packageName is incompatible: $message")
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.e(TAG, "Package $packageName is invalid: $message")
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Package $packageName installation failed - insufficient storage: $message")
            }
            else -> {
                Log.e(TAG, "Package $packageName installation failed with unknown status $status: $message")
            }
        }
    }

    private fun handleUninstallResult(packageName: String?, status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package $packageName uninstalled successfully")
                // TODO: Send success event to MDM server
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Package $packageName uninstall requires user action")
            }
            else -> {
                Log.e(TAG, "Package $packageName uninstall failed with status $status: $message")
            }
        }
    }
}
