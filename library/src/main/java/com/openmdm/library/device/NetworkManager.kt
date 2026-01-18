package com.openmdm.library.device

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import com.openmdm.library.policy.WifiNetworkConfig
import com.openmdm.library.policy.WifiSecurityType

/**
 * Network Configuration Manager
 *
 * Manages WiFi network provisioning with support for:
 * - Android 10+: WifiNetworkSuggestion API
 * - Android 9 and below: WifiConfiguration API (deprecated but functional)
 * - WPA2, WPA3, Open, and Enterprise networks
 *
 * Usage:
 * ```kotlin
 * val networkManager = NetworkManager.create(context, adminComponent)
 * networkManager.addWifiNetwork(WifiNetworkConfig(
 *     ssid = "MyNetwork",
 *     password = "password123",
 *     securityType = WifiSecurityType.WPA2
 * ))
 * ```
 */
class NetworkManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        /**
         * Create NetworkManager with a DeviceAdminReceiver class
         */
        fun <T : android.app.admin.DeviceAdminReceiver> create(
            context: Context,
            adminReceiverClass: Class<T>
        ): NetworkManager {
            val adminComponent = ComponentName(context, adminReceiverClass)
            return NetworkManager(context, adminComponent)
        }

        /**
         * Create NetworkManager with explicit ComponentName
         */
        fun create(context: Context, adminComponent: ComponentName): NetworkManager {
            return NetworkManager(context, adminComponent)
        }
    }

    // ============================================
    // Status Checks
    // ============================================

    private fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(context.packageName)

    // ============================================
    // WiFi Network Configuration
    // ============================================

    /**
     * Add a WiFi network configuration.
     *
     * Uses WifiNetworkSuggestion on Android 10+ and WifiConfiguration on older versions.
     */
    @SuppressLint("MissingPermission")
    fun addWifiNetwork(config: WifiNetworkConfig): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addNetworkViaSuggestion(config)
        } else {
            addNetworkViaConfiguration(config)
        }
    }

    /**
     * Add network using WifiNetworkSuggestion API (Android 10+)
     */
    @SuppressLint("MissingPermission")
    private fun addNetworkViaSuggestion(config: WifiNetworkConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val suggestionBuilder = WifiNetworkSuggestion.Builder()
            .setSsid(config.ssid)
            .setIsHiddenSsid(config.hidden)

        when (config.securityType) {
            WifiSecurityType.OPEN -> {
                // No security configuration needed
            }
            WifiSecurityType.WEP -> {
                // WEP is deprecated and not supported in suggestions API
                throw UnsupportedOperationException("WEP is not supported on Android 10+")
            }
            WifiSecurityType.WPA, WifiSecurityType.WPA2 -> {
                config.password?.let { suggestionBuilder.setWpa2Passphrase(it) }
            }
            WifiSecurityType.WPA3 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    config.password?.let { suggestionBuilder.setWpa3Passphrase(it) }
                }
            }
            WifiSecurityType.WPA2_ENTERPRISE, WifiSecurityType.WPA3_ENTERPRISE -> {
                // Enterprise config requires WifiEnterpriseConfig
                config.eapConfig?.let { eap ->
                    val enterpriseConfig = android.net.wifi.WifiEnterpriseConfig().apply {
                        when (eap.method.uppercase()) {
                            "PEAP" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.PEAP
                            "TLS" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.TLS
                            "TTLS" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.TTLS
                            "PWD" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.PWD
                            "SIM" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.SIM
                            "AKA" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.AKA
                        }

                        eap.phase2Method?.let { phase2 ->
                            when (phase2.uppercase()) {
                                "MSCHAPV2" -> phase2Method = android.net.wifi.WifiEnterpriseConfig.Phase2.MSCHAPV2
                                "GTC" -> phase2Method = android.net.wifi.WifiEnterpriseConfig.Phase2.GTC
                                "PAP" -> phase2Method = android.net.wifi.WifiEnterpriseConfig.Phase2.PAP
                            }
                        }

                        eap.identity?.let { identity = it }
                        eap.anonymousIdentity?.let { anonymousIdentity = it }
                    }

                    if (config.securityType == WifiSecurityType.WPA3_ENTERPRISE) {
                        suggestionBuilder.setWpa3EnterpriseConfig(enterpriseConfig)
                    } else {
                        suggestionBuilder.setWpa2EnterpriseConfig(enterpriseConfig)
                    }
                }
            }
        }

        val suggestion = suggestionBuilder.build()
        val suggestions = listOf(suggestion)

        // Remove existing suggestions first
        wifiManager.removeNetworkSuggestions(suggestions)

        // Add new suggestion
        val status = wifiManager.addNetworkSuggestions(suggestions)
        if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            throw RuntimeException("Failed to add network suggestion: status=$status")
        }
    }

    /**
     * Add network using WifiConfiguration API (Android 9 and below)
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun addNetworkViaConfiguration(config: WifiNetworkConfig) {
        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"${config.ssid}\""
            hiddenSSID = config.hidden
            priority = config.priority

            when (config.securityType) {
                WifiSecurityType.OPEN -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                WifiSecurityType.WEP -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                    config.password?.let {
                        if (it.matches(Regex("^[0-9A-Fa-f]+$")) && (it.length == 10 || it.length == 26)) {
                            wepKeys[0] = it
                        } else {
                            wepKeys[0] = "\"$it\""
                        }
                    }
                    wepTxKeyIndex = 0
                }
                WifiSecurityType.WPA, WifiSecurityType.WPA2, WifiSecurityType.WPA3 -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    config.password?.let { preSharedKey = "\"$it\"" }
                }
                WifiSecurityType.WPA2_ENTERPRISE, WifiSecurityType.WPA3_ENTERPRISE -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP)
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)

                    config.eapConfig?.let { eap ->
                        enterpriseConfig = android.net.wifi.WifiEnterpriseConfig().apply {
                            when (eap.method.uppercase()) {
                                "PEAP" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.PEAP
                                "TLS" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.TLS
                                "TTLS" -> eapMethod = android.net.wifi.WifiEnterpriseConfig.Eap.TTLS
                            }

                            eap.phase2Method?.let { phase2 ->
                                when (phase2.uppercase()) {
                                    "MSCHAPV2" -> phase2Method = android.net.wifi.WifiEnterpriseConfig.Phase2.MSCHAPV2
                                    "GTC" -> phase2Method = android.net.wifi.WifiEnterpriseConfig.Phase2.GTC
                                }
                            }

                            eap.identity?.let { identity = it }
                            eap.anonymousIdentity?.let { anonymousIdentity = it }
                        }
                    }
                }
            }
        }

        // Remove existing network with same SSID
        val existingNetworks = wifiManager.configuredNetworks ?: emptyList()
        existingNetworks
            .filter { it.SSID == wifiConfig.SSID }
            .forEach { wifiManager.removeNetwork(it.networkId) }

        // Add new network
        val networkId = wifiManager.addNetwork(wifiConfig)
        if (networkId == -1) {
            throw RuntimeException("Failed to add network configuration")
        }

        // Enable and save
        wifiManager.enableNetwork(networkId, config.autoConnect)
        wifiManager.saveConfiguration()
    }

    /**
     * Remove a WiFi network by SSID
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun removeWifiNetwork(ssid: String): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For suggestions, we need to build the same suggestion to remove it
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .build()
            wifiManager.removeNetworkSuggestions(listOf(suggestion))
        } else {
            // For configured networks
            val quotedSsid = "\"$ssid\""
            val networks = wifiManager.configuredNetworks ?: emptyList()
            networks
                .filter { it.SSID == quotedSsid }
                .forEach { wifiManager.removeNetwork(it.networkId) }
            wifiManager.saveConfiguration()
        }
    }

    /**
     * Add multiple WiFi networks
     */
    fun addWifiNetworks(configs: List<WifiNetworkConfig>): Map<String, Boolean> {
        return configs.associate { config ->
            config.ssid to addWifiNetwork(config).isSuccess
        }
    }

    // ============================================
    // Network Status
    // ============================================

    /**
     * Get current WiFi status
     */
    @SuppressLint("MissingPermission")
    fun getWifiStatus(): WifiStatus {
        val wifiInfo = wifiManager.connectionInfo
        val isConnected = isWifiConnected()

        return WifiStatus(
            enabled = wifiManager.isWifiEnabled,
            connected = isConnected,
            ssid = if (isConnected) wifiInfo?.ssid?.removeSurrounding("\"") else null,
            bssid = if (isConnected) wifiInfo?.bssid else null,
            linkSpeed = if (isConnected) wifiInfo?.linkSpeed ?: 0 else 0,
            rssi = if (isConnected) wifiInfo?.rssi ?: 0 else 0,
            ipAddress = if (isConnected) formatIpAddress(wifiInfo?.ipAddress ?: 0) else null
        )
    }

    /**
     * Check if WiFi is connected
     */
    private fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Format IP address from int
     */
    private fun formatIpAddress(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    /**
     * Get list of configured/suggested networks
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun getConfiguredNetworks(): List<String> {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            wifiManager.configuredNetworks?.map {
                it.SSID?.removeSurrounding("\"") ?: ""
            }?.filter { it.isNotEmpty() } ?: emptyList()
        } else {
            // On Android 10+, we can't get the list of suggested networks
            // This would need to be tracked by the app
            emptyList()
        }
    }

    // ============================================
    // Network Connectivity
    // ============================================

    /**
     * Get current network type
     */
    fun getNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork
            ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
        }
    }

    /**
     * Check if device has internet connectivity
     */
    fun hasInternetConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Get overall network status
     */
    fun getNetworkStatus(): NetworkStatus {
        return NetworkStatus(
            connected = hasInternetConnectivity(),
            networkType = getNetworkType(),
            wifi = getWifiStatus()
        )
    }
}

/**
 * WiFi connection status
 */
data class WifiStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val ssid: String?,
    val bssid: String?,
    val linkSpeed: Int,
    val rssi: Int,
    val ipAddress: String?
)

/**
 * Network type enum
 */
enum class NetworkType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER
}

/**
 * Overall network status
 */
data class NetworkStatus(
    val connected: Boolean,
    val networkType: NetworkType,
    val wifi: WifiStatus
)
