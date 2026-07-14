package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Managed configurations (app restrictions).
 *
 * This is how Android Enterprise configures a *third-party* app without that app
 * needing to know anything about MDM: the DPC writes a bundle of key/value
 * settings, and the app reads them through `RestrictionsManager`. It is the
 * standard mechanism — Intune, Workspace ONE, and managed Google Play all use
 * it — and OpenMDM had no support for it at all.
 *
 * The alternative, which is what fleets end up doing without this, is
 * side-loading a per-customer build of every app just to change a server URL.
 *
 * ```kotlin
 * deviceManager.getManagedConfigurationManager().setConfiguration(
 *     "com.example.player",
 *     mapOf("server_url" to "https://cdn.example.com", "kiosk" to true),
 * )
 * ```
 *
 * The receiving app must declare the keys in an `app_restrictions.xml`; keys it
 * does not declare are ignored by the platform. Requires Device Owner or Profile
 * Owner.
 */
class ManagedConfigurationManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName,
) {
    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /**
     * Replace an app's managed configuration.
     *
     * This is a **replace**, not a merge: the platform's model is that the DPC
     * declares the whole configuration, so a key omitted here is a key removed.
     * Passing an empty map clears the configuration entirely.
     */
    fun setConfiguration(packageName: String, config: Map<String, Any?>): Result<Unit> =
        runCatching {
            require(isManagementOwner()) {
                "Managed configurations require Device Owner or Profile Owner"
            }

            val bundle = Bundle().apply {
                config.forEach { (key, value) ->
                    when (value) {
                        null -> {} // omit: null and "absent" are the same to the platform
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Double -> putFloat(key, value.toFloat())
                        is Array<*> -> putStringArray(
                            key,
                            value.map { it?.toString() ?: "" }.toTypedArray(),
                        )
                        is List<*> -> putStringArray(
                            key,
                            value.map { it?.toString() ?: "" }.toTypedArray(),
                        )
                        // A type the platform's restriction bundle cannot carry.
                        // Stringify rather than drop it silently.
                        else -> putString(key, value.toString())
                    }
                }
            }

            dpm.setApplicationRestrictions(adminComponent, packageName, bundle)
            Log.i(TAG, "Managed configuration applied to $packageName (${config.size} keys)")
        }

    /** The configuration currently applied to an app. */
    fun getConfiguration(packageName: String): Map<String, Any?> {
        val bundle = dpm.getApplicationRestrictions(adminComponent, packageName)
        return bundle.keySet().associateWith { @Suppress("DEPRECATION") bundle.get(it) }
    }

    /** Remove an app's managed configuration entirely. */
    fun clearConfiguration(packageName: String): Result<Unit> =
        setConfiguration(packageName, emptyMap())

    private fun isManagementOwner(): Boolean =
        dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)

    companion object {
        private const val TAG = "ManagedConfig"

        fun create(context: Context, adminComponent: ComponentName) =
            ManagedConfigurationManager(context, adminComponent)
    }
}
