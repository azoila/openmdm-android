package com.openmdm.library.enrollment

import android.app.admin.DevicePolicyManager
import android.os.Build
import android.os.PersistableBundle
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * QR Code Enrollment Parser
 *
 * Parses and generates QR codes for Android device provisioning.
 * Supports the standard Android provisioning format (EXTRA_PROVISIONING_*)
 * and OpenMDM-specific extensions.
 *
 * Supports three QR code formats:
 * 1. JSON format (standard Android)
 * 2. Key-value URL-encoded format
 * 3. OpenMDM simple format (server URL + device ID)
 *
 * Usage:
 * ```kotlin
 * // Parse a QR code
 * val config = QREnrollmentParser.parseQRCode(qrContent)
 *
 * // Generate a QR code
 * val qrContent = QREnrollmentParser.generateQRCode(
 *     serverUrl = "https://mdm.example.com",
 *     deviceSecret = "abc123"
 * )
 * ```
 */
object QREnrollmentParser {

    // Standard Android provisioning extras
    const val EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME =
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME"
    const val EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME =
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
    const val EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION =
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"
    const val EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM =
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"
    const val EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM =
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"
    const val EXTRA_PROVISIONING_SKIP_ENCRYPTION =
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION"
    const val EXTRA_PROVISIONING_WIFI_SSID =
        "android.app.extra.PROVISIONING_WIFI_SSID"
    const val EXTRA_PROVISIONING_WIFI_HIDDEN =
        "android.app.extra.PROVISIONING_WIFI_HIDDEN"
    const val EXTRA_PROVISIONING_WIFI_SECURITY_TYPE =
        "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"
    const val EXTRA_PROVISIONING_WIFI_PASSWORD =
        "android.app.extra.PROVISIONING_WIFI_PASSWORD"
    const val EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE =
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
    const val EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED =
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED"
    const val EXTRA_PROVISIONING_LOCALE =
        "android.app.extra.PROVISIONING_LOCALE"
    const val EXTRA_PROVISIONING_TIME_ZONE =
        "android.app.extra.PROVISIONING_TIME_ZONE"
    const val EXTRA_PROVISIONING_LOCAL_TIME =
        "android.app.extra.PROVISIONING_LOCAL_TIME"
    const val EXTRA_PROVISIONING_DISCLAIMERS =
        "android.app.extra.PROVISIONING_DISCLAIMERS"
    const val EXTRA_PROVISIONING_SKIP_USER_CONSENT =
        "android.app.extra.PROVISIONING_SKIP_USER_CONSENT"
    const val EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS =
        "android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS"
    const val EXTRA_PROVISIONING_USE_MOBILE_DATA =
        "android.app.extra.PROVISIONING_USE_MOBILE_DATA"

    // OpenMDM-specific extras (stored in admin extras bundle)
    const val OPENMDM_SERVER_URL = "openmdm.server_url"
    const val OPENMDM_DEVICE_SECRET = "openmdm.device_secret"
    const val OPENMDM_POLICY_ID = "openmdm.policy_id"
    const val OPENMDM_GROUP_ID = "openmdm.group_id"
    const val OPENMDM_CONFIG_URL = "openmdm.config_url"
    const val OPENMDM_ENROLLMENT_TOKEN = "openmdm.enrollment_token"

    /**
     * Parse a QR code content string into an EnrollmentConfig.
     *
     * Detects the format automatically:
     * - JSON starting with '{'
     * - URL-encoded key-value pairs
     * - Simple OpenMDM format "server_url|device_secret"
     */
    fun parseQRCode(content: String): Result<EnrollmentConfig> = runCatching {
        val trimmed = content.trim()

        when {
            trimmed.startsWith("{") -> parseJsonFormat(trimmed)
            trimmed.contains("=") -> parseKeyValueFormat(trimmed)
            trimmed.contains("|") -> parseSimpleFormat(trimmed)
            trimmed.startsWith("http") -> EnrollmentConfig(serverUrl = trimmed)
            else -> throw IllegalArgumentException("Unknown QR code format")
        }
    }

    /**
     * Parse JSON format QR code (standard Android provisioning format)
     */
    private fun parseJsonFormat(json: String): EnrollmentConfig {
        val jsonObject = JSONObject(json)

        // Extract WiFi config
        val wifiConfig = if (jsonObject.has(EXTRA_PROVISIONING_WIFI_SSID)) {
            WifiProvisioningConfig(
                ssid = jsonObject.getString(EXTRA_PROVISIONING_WIFI_SSID),
                hidden = jsonObject.optBoolean(EXTRA_PROVISIONING_WIFI_HIDDEN, false),
                securityType = jsonObject.optString(EXTRA_PROVISIONING_WIFI_SECURITY_TYPE, "NONE"),
                password = jsonObject.optString(EXTRA_PROVISIONING_WIFI_PASSWORD, null)
            )
        } else null

        // Extract admin extras bundle
        val adminExtras = if (jsonObject.has(EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)) {
            parseAdminExtras(jsonObject.getJSONObject(EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE))
        } else null

        // Extract OpenMDM-specific fields from admin extras or top level
        val serverUrl = adminExtras?.serverUrl
            ?: jsonObject.optString(OPENMDM_SERVER_URL, null)
            ?: jsonObject.optString("serverUrl", null)

        val deviceSecret = adminExtras?.deviceSecret
            ?: jsonObject.optString(OPENMDM_DEVICE_SECRET, null)
            ?: jsonObject.optString("deviceSecret", null)

        val policyId = adminExtras?.policyId
            ?: jsonObject.optString(OPENMDM_POLICY_ID, null)
            ?: jsonObject.optString("policyId", null)

        val groupId = adminExtras?.groupId
            ?: jsonObject.optString(OPENMDM_GROUP_ID, null)
            ?: jsonObject.optString("groupId", null)

        return EnrollmentConfig(
            adminPackageName = jsonObject.optString(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, null),
            adminComponentName = jsonObject.optString(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, null),
            downloadUrl = jsonObject.optString(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION, null),
            packageChecksum = jsonObject.optString(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM, null),
            signatureChecksum = jsonObject.optString(EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM, null),
            skipEncryption = jsonObject.optBoolean(EXTRA_PROVISIONING_SKIP_ENCRYPTION, false),
            wifiConfig = wifiConfig,
            locale = jsonObject.optString(EXTRA_PROVISIONING_LOCALE, null),
            timeZone = jsonObject.optString(EXTRA_PROVISIONING_TIME_ZONE, null),
            leaveAllSystemAppsEnabled = jsonObject.optBoolean(EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED, false),
            skipUserConsent = jsonObject.optBoolean(EXTRA_PROVISIONING_SKIP_USER_CONSENT, false),
            skipEducationScreens = jsonObject.optBoolean(EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS, false),
            useMobileData = jsonObject.optBoolean(EXTRA_PROVISIONING_USE_MOBILE_DATA, false),
            serverUrl = serverUrl,
            deviceSecret = deviceSecret,
            policyId = policyId,
            groupId = groupId,
            configUrl = adminExtras?.configUrl,
            enrollmentToken = adminExtras?.enrollmentToken
        )
    }

    /**
     * Parse admin extras JSON object
     */
    private fun parseAdminExtras(json: JSONObject): AdminExtras {
        return AdminExtras(
            serverUrl = json.optString(OPENMDM_SERVER_URL, null)
                ?: json.optString("serverUrl", null),
            deviceSecret = json.optString(OPENMDM_DEVICE_SECRET, null)
                ?: json.optString("deviceSecret", null),
            policyId = json.optString(OPENMDM_POLICY_ID, null)
                ?: json.optString("policyId", null),
            groupId = json.optString(OPENMDM_GROUP_ID, null)
                ?: json.optString("groupId", null),
            configUrl = json.optString(OPENMDM_CONFIG_URL, null)
                ?: json.optString("configUrl", null),
            enrollmentToken = json.optString(OPENMDM_ENROLLMENT_TOKEN, null)
                ?: json.optString("enrollmentToken", null)
        )
    }

    /**
     * Parse URL-encoded key-value format
     */
    private fun parseKeyValueFormat(content: String): EnrollmentConfig {
        val params = content.split("&").associate { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            } else {
                parts[0] to ""
            }
        }

        val wifiConfig = params["wifi_ssid"]?.let { ssid ->
            WifiProvisioningConfig(
                ssid = ssid,
                hidden = params["wifi_hidden"]?.toBoolean() ?: false,
                securityType = params["wifi_security"] ?: "NONE",
                password = params["wifi_password"]
            )
        }

        return EnrollmentConfig(
            adminPackageName = params["admin_package"] ?: params[EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME],
            adminComponentName = params["admin_component"] ?: params[EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME],
            downloadUrl = params["download_url"] ?: params[EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION],
            packageChecksum = params["checksum"] ?: params[EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM],
            signatureChecksum = params["signature_checksum"] ?: params[EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM],
            wifiConfig = wifiConfig,
            serverUrl = params["server_url"] ?: params["serverUrl"] ?: params[OPENMDM_SERVER_URL],
            deviceSecret = params["device_secret"] ?: params["deviceSecret"] ?: params[OPENMDM_DEVICE_SECRET],
            policyId = params["policy_id"] ?: params["policyId"] ?: params[OPENMDM_POLICY_ID],
            groupId = params["group_id"] ?: params["groupId"] ?: params[OPENMDM_GROUP_ID],
            configUrl = params["config_url"] ?: params["configUrl"] ?: params[OPENMDM_CONFIG_URL],
            enrollmentToken = params["enrollment_token"] ?: params["enrollmentToken"] ?: params[OPENMDM_ENROLLMENT_TOKEN]
        )
    }

    /**
     * Parse simple OpenMDM format: "server_url|device_secret" or "server_url|device_secret|policy_id"
     */
    private fun parseSimpleFormat(content: String): EnrollmentConfig {
        val parts = content.split("|")
        return EnrollmentConfig(
            serverUrl = parts.getOrNull(0),
            deviceSecret = parts.getOrNull(1),
            policyId = parts.getOrNull(2),
            groupId = parts.getOrNull(3)
        )
    }

    /**
     * Generate a QR code content string for device provisioning.
     *
     * @param format Output format: JSON, KEY_VALUE, or SIMPLE
     */
    fun generateQRCode(
        serverUrl: String,
        adminPackageName: String? = null,
        adminComponentName: String? = null,
        downloadUrl: String? = null,
        packageChecksum: String? = null,
        signatureChecksum: String? = null,
        deviceSecret: String? = null,
        policyId: String? = null,
        groupId: String? = null,
        configUrl: String? = null,
        enrollmentToken: String? = null,
        wifiSsid: String? = null,
        wifiPassword: String? = null,
        wifiSecurityType: String? = null,
        wifiHidden: Boolean = false,
        skipEncryption: Boolean = false,
        leaveAllSystemAppsEnabled: Boolean = false,
        skipUserConsent: Boolean = false,
        skipEducationScreens: Boolean = false,
        useMobileData: Boolean = false,
        locale: String? = null,
        timeZone: String? = null,
        format: QRFormat = QRFormat.JSON
    ): String {
        return when (format) {
            QRFormat.JSON -> generateJsonFormat(
                serverUrl, adminPackageName, adminComponentName, downloadUrl,
                packageChecksum, signatureChecksum, deviceSecret, policyId, groupId,
                configUrl, enrollmentToken, wifiSsid, wifiPassword, wifiSecurityType,
                wifiHidden, skipEncryption, leaveAllSystemAppsEnabled, skipUserConsent,
                skipEducationScreens, useMobileData, locale, timeZone
            )
            QRFormat.KEY_VALUE -> generateKeyValueFormat(
                serverUrl, adminPackageName, adminComponentName, downloadUrl,
                packageChecksum, deviceSecret, policyId, groupId,
                wifiSsid, wifiPassword, wifiSecurityType
            )
            QRFormat.SIMPLE -> generateSimpleFormat(serverUrl, deviceSecret, policyId, groupId)
        }
    }

    private fun generateJsonFormat(
        serverUrl: String,
        adminPackageName: String?,
        adminComponentName: String?,
        downloadUrl: String?,
        packageChecksum: String?,
        signatureChecksum: String?,
        deviceSecret: String?,
        policyId: String?,
        groupId: String?,
        configUrl: String?,
        enrollmentToken: String?,
        wifiSsid: String?,
        wifiPassword: String?,
        wifiSecurityType: String?,
        wifiHidden: Boolean,
        skipEncryption: Boolean,
        leaveAllSystemAppsEnabled: Boolean,
        skipUserConsent: Boolean,
        skipEducationScreens: Boolean,
        useMobileData: Boolean,
        locale: String?,
        timeZone: String?
    ): String {
        val json = JSONObject()

        adminPackageName?.let { json.put(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, it) }
        adminComponentName?.let { json.put(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, it) }
        downloadUrl?.let { json.put(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION, it) }
        packageChecksum?.let { json.put(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM, it) }
        signatureChecksum?.let { json.put(EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM, it) }

        if (skipEncryption) json.put(EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
        if (leaveAllSystemAppsEnabled) json.put(EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED, true)
        if (skipUserConsent) json.put(EXTRA_PROVISIONING_SKIP_USER_CONSENT, true)
        if (skipEducationScreens) json.put(EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS, true)
        if (useMobileData) json.put(EXTRA_PROVISIONING_USE_MOBILE_DATA, true)

        locale?.let { json.put(EXTRA_PROVISIONING_LOCALE, it) }
        timeZone?.let { json.put(EXTRA_PROVISIONING_TIME_ZONE, it) }

        // WiFi configuration
        wifiSsid?.let {
            json.put(EXTRA_PROVISIONING_WIFI_SSID, it)
            if (wifiHidden) json.put(EXTRA_PROVISIONING_WIFI_HIDDEN, true)
            wifiSecurityType?.let { sec -> json.put(EXTRA_PROVISIONING_WIFI_SECURITY_TYPE, sec) }
            wifiPassword?.let { pwd -> json.put(EXTRA_PROVISIONING_WIFI_PASSWORD, pwd) }
        }

        // Admin extras bundle with OpenMDM-specific data
        val adminExtras = JSONObject()
        adminExtras.put(OPENMDM_SERVER_URL, serverUrl)
        deviceSecret?.let { adminExtras.put(OPENMDM_DEVICE_SECRET, it) }
        policyId?.let { adminExtras.put(OPENMDM_POLICY_ID, it) }
        groupId?.let { adminExtras.put(OPENMDM_GROUP_ID, it) }
        configUrl?.let { adminExtras.put(OPENMDM_CONFIG_URL, it) }
        enrollmentToken?.let { adminExtras.put(OPENMDM_ENROLLMENT_TOKEN, it) }

        json.put(EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, adminExtras)

        return json.toString()
    }

    private fun generateKeyValueFormat(
        serverUrl: String,
        adminPackageName: String?,
        adminComponentName: String?,
        downloadUrl: String?,
        packageChecksum: String?,
        deviceSecret: String?,
        policyId: String?,
        groupId: String?,
        wifiSsid: String?,
        wifiPassword: String?,
        wifiSecurityType: String?
    ): String {
        val params = mutableListOf<String>()

        fun addParam(key: String, value: String?) {
            value?.let {
                params.add("${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(it, "UTF-8")}")
            }
        }

        addParam("server_url", serverUrl)
        addParam("admin_package", adminPackageName)
        addParam("admin_component", adminComponentName)
        addParam("download_url", downloadUrl)
        addParam("checksum", packageChecksum)
        addParam("device_secret", deviceSecret)
        addParam("policy_id", policyId)
        addParam("group_id", groupId)
        addParam("wifi_ssid", wifiSsid)
        addParam("wifi_password", wifiPassword)
        addParam("wifi_security", wifiSecurityType)

        return params.joinToString("&")
    }

    private fun generateSimpleFormat(
        serverUrl: String,
        deviceSecret: String?,
        policyId: String?,
        groupId: String?
    ): String {
        return listOfNotNull(serverUrl, deviceSecret, policyId, groupId).joinToString("|")
    }

    /**
     * Convert EnrollmentConfig to PersistableBundle for passing to provisioning.
     *
     * This creates the admin extras bundle that will be passed to the
     * DeviceAdminReceiver.onProfileProvisioningComplete() callback.
     */
    fun configToPersistableBundle(config: EnrollmentConfig): PersistableBundle {
        return PersistableBundle().apply {
            config.serverUrl?.let { putString(OPENMDM_SERVER_URL, it) }
            config.deviceSecret?.let { putString(OPENMDM_DEVICE_SECRET, it) }
            config.policyId?.let { putString(OPENMDM_POLICY_ID, it) }
            config.groupId?.let { putString(OPENMDM_GROUP_ID, it) }
            config.configUrl?.let { putString(OPENMDM_CONFIG_URL, it) }
            config.enrollmentToken?.let { putString(OPENMDM_ENROLLMENT_TOKEN, it) }
        }
    }

    /**
     * Extract EnrollmentConfig from PersistableBundle received in onProfileProvisioningComplete.
     */
    fun bundleToConfig(bundle: PersistableBundle?): EnrollmentConfig? {
        if (bundle == null) return null

        val serverUrl = bundle.getString(OPENMDM_SERVER_URL)
            ?: bundle.getString("serverUrl")
            ?: return null

        return EnrollmentConfig(
            serverUrl = serverUrl,
            deviceSecret = bundle.getString(OPENMDM_DEVICE_SECRET)
                ?: bundle.getString("deviceSecret"),
            policyId = bundle.getString(OPENMDM_POLICY_ID)
                ?: bundle.getString("policyId"),
            groupId = bundle.getString(OPENMDM_GROUP_ID)
                ?: bundle.getString("groupId"),
            configUrl = bundle.getString(OPENMDM_CONFIG_URL)
                ?: bundle.getString("configUrl"),
            enrollmentToken = bundle.getString(OPENMDM_ENROLLMENT_TOKEN)
                ?: bundle.getString("enrollmentToken")
        )
    }
}

/**
 * QR code output format
 */
enum class QRFormat {
    JSON,       // Standard Android provisioning JSON format
    KEY_VALUE,  // URL-encoded key-value pairs
    SIMPLE      // Simple pipe-separated format
}

/**
 * Complete enrollment configuration parsed from QR code
 */
data class EnrollmentConfig(
    // Device admin configuration
    val adminPackageName: String? = null,
    val adminComponentName: String? = null,
    val downloadUrl: String? = null,
    val packageChecksum: String? = null,
    val signatureChecksum: String? = null,

    // Provisioning options
    val skipEncryption: Boolean = false,
    val leaveAllSystemAppsEnabled: Boolean = false,
    val skipUserConsent: Boolean = false,
    val skipEducationScreens: Boolean = false,
    val useMobileData: Boolean = false,
    val locale: String? = null,
    val timeZone: String? = null,

    // WiFi configuration
    val wifiConfig: WifiProvisioningConfig? = null,

    // OpenMDM-specific
    val serverUrl: String? = null,
    val deviceSecret: String? = null,
    val policyId: String? = null,
    val groupId: String? = null,
    val configUrl: String? = null,
    val enrollmentToken: String? = null
)

/**
 * WiFi configuration for provisioning
 */
data class WifiProvisioningConfig(
    val ssid: String,
    val hidden: Boolean = false,
    val securityType: String = "NONE", // NONE, WEP, WPA, WPA2
    val password: String? = null
)

/**
 * Admin extras extracted from QR code
 */
private data class AdminExtras(
    val serverUrl: String?,
    val deviceSecret: String?,
    val policyId: String?,
    val groupId: String?,
    val configUrl: String?,
    val enrollmentToken: String?
)
