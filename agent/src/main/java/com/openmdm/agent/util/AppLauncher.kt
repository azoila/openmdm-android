package com.openmdm.agent.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for launching apps, URLs, and intents.
 *
 * Provides a centralized way to launch different types of content:
 * - Installed apps by package name
 * - Web URLs in the browser
 * - Custom intents with actions and URIs
 */
@Singleton
class AppLauncher @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Launch an installed app by its package name.
     *
     * @param packageName The package name of the app to launch
     * @return true if app was launched successfully, false otherwise
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Launch a web URL in the default browser.
     *
     * @param url The URL to open
     * @return true if URL was opened successfully, false otherwise
     */
    fun launchWebUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Launch a custom intent with the given action and optional URI.
     *
     * @param action The intent action (e.g., Intent.ACTION_VIEW)
     * @param uri Optional URI for the intent data
     * @return true if intent was launched successfully, false otherwise
     */
    fun launchIntent(action: String?, uri: String?): Boolean {
        if (action.isNullOrBlank()) return false

        return try {
            val intent = if (uri != null) {
                Intent(action, Uri.parse(uri))
            } else {
                Intent(action)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
