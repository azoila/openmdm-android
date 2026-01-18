package com.openmdm.library.device

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.openmdm.library.policy.HardwarePolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware Control Manager
 *
 * Manages hardware toggles (WiFi, Bluetooth, GPS, USB, Mobile Data, NFC)
 * with periodic enforcement to maintain policy compliance.
 *
 * Uses appropriate APIs based on Android version:
 * - Android 10+: User restrictions for WiFi/Bluetooth
 * - Legacy: Direct adapter control where available
 *
 * Usage:
 * ```kotlin
 * val hardwareManager = HardwareManager.create(context, adminComponent)
 * hardwareManager.setWifiEnabled(false)
 * hardwareManager.startEnforcement(HardwarePolicy(wifiEnabled = false))
 * ```
 */
class HardwareManager private constructor(
    private val context: Context,
    private val adminComponent: ComponentName
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _enforcementState = MutableStateFlow<EnforcementState>(EnforcementState.Idle)
    val enforcementState: StateFlow<EnforcementState> = _enforcementState.asStateFlow()

    private var enforcementJob: Job? = null
    private val isEnforcing = AtomicBoolean(false)
    private var currentPolicy: HardwarePolicy? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        /**
         * Create HardwareManager with a DeviceAdminReceiver class
         */
        fun <T : android.app.admin.DeviceAdminReceiver> create(
            context: Context,
            adminReceiverClass: Class<T>
        ): HardwareManager {
            val adminComponent = ComponentName(context, adminReceiverClass)
            return HardwareManager(context, adminComponent)
        }

        /**
         * Create HardwareManager with explicit ComponentName
         */
        fun create(context: Context, adminComponent: ComponentName): HardwareManager {
            return HardwareManager(context, adminComponent)
        }
    }

    // ============================================
    // Status Checks
    // ============================================

    private fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(context.packageName)

    // ============================================
    // WiFi Control
    // ============================================

    /**
     * Enable or disable WiFi.
     *
     * Android 10+: Uses user restriction DISALLOW_CONFIG_WIFI + DISALLOW_CHANGE_WIFI_STATE
     * Android 9 and below: Uses WifiManager.setWifiEnabled (deprecated but functional)
     */
    @SuppressLint("WifiManagerPotentialLeak")
    fun setWifiEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "WiFi control requires Device Owner" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: Use user restrictions to control WiFi state
            // We disable WiFi by preventing configuration changes
            if (enabled) {
                devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_CHANGE_WIFI_STATE)
                devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
            } else {
                // First turn off WiFi using shell command, then restrict
                Runtime.getRuntime().exec(arrayOf("svc", "wifi", "disable")).waitFor()
                devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_CHANGE_WIFI_STATE)
                devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
            }
        } else {
            // Android 9 and below: Direct WiFi control
            @Suppress("DEPRECATION")
            wifiManager?.isWifiEnabled = enabled
        }
    }

    /**
     * Get current WiFi state
     */
    fun isWifiEnabled(): Boolean {
        return wifiManager?.isWifiEnabled ?: false
    }

    // ============================================
    // Bluetooth Control
    // ============================================

    /**
     * Enable or disable Bluetooth.
     *
     * Android 13+: Uses user restriction DISALLOW_BLUETOOTH
     * Android 12 and below: Uses BluetoothAdapter enable/disable
     */
    @SuppressLint("MissingPermission")
    fun setBluetoothEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Bluetooth control requires Device Owner" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: Use user restriction
            if (enabled) {
                devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH)
                devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BLUETOOTH)
            } else {
                // Turn off Bluetooth first, then restrict
                bluetoothManager?.adapter?.let { adapter ->
                    @Suppress("DEPRECATION")
                    adapter.disable()
                }
                devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH)
                devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BLUETOOTH)
            }
        } else {
            // Android 12 and below: Direct Bluetooth control
            bluetoothManager?.adapter?.let { adapter ->
                @Suppress("DEPRECATION")
                if (enabled) {
                    adapter.enable()
                } else {
                    adapter.disable()
                }
            }
        }
    }

    /**
     * Get current Bluetooth state
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothManager?.adapter?.isEnabled ?: false
    }

    // ============================================
    // GPS/Location Control
    // ============================================

    /**
     * Enable or disable GPS/Location services.
     *
     * Uses DevicePolicyManager.setLocationEnabled (Android 9+)
     */
    fun setGpsEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "GPS control requires Device Owner" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            devicePolicyManager.setLocationEnabled(adminComponent, enabled)
        } else {
            // Android 8.1 and below: Use Settings.Secure
            val mode = if (enabled) {
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
            } else {
                Settings.Secure.LOCATION_MODE_OFF
            }
            @Suppress("DEPRECATION")
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, mode)
        }
    }

    /**
     * Get current GPS/Location state
     */
    fun isGpsEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            val mode = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            mode != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    // ============================================
    // USB File Transfer Control
    // ============================================

    /**
     * Enable or disable USB file transfer.
     *
     * Uses DISALLOW_USB_FILE_TRANSFER user restriction.
     */
    fun setUsbEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "USB control requires Device Owner" }

        if (enabled) {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
        } else {
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
        }
    }

    /**
     * Get current USB file transfer state (inverse of restriction)
     */
    fun isUsbEnabled(): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return !userManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)
    }

    // ============================================
    // Mobile Data Control
    // ============================================

    /**
     * Enable or disable mobile data.
     *
     * Uses DISALLOW_CONFIG_MOBILE_NETWORKS and shell commands.
     * Note: Full mobile data control is limited on newer Android versions.
     */
    fun setMobileDataEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Mobile data control requires Device Owner" }

        if (enabled) {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            // Enable mobile data via shell
            Runtime.getRuntime().exec(arrayOf("svc", "data", "enable")).waitFor()
        } else {
            // Disable mobile data via shell, then restrict
            Runtime.getRuntime().exec(arrayOf("svc", "data", "disable")).waitFor()
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        }
    }

    // ============================================
    // NFC Control
    // ============================================

    /**
     * Enable or disable NFC.
     *
     * Uses DISALLOW_OUTGOING_BEAM and shell commands.
     */
    fun setNfcEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "NFC control requires Device Owner" }

        if (enabled) {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_BEAM)
            Runtime.getRuntime().exec(arrayOf("svc", "nfc", "enable")).waitFor()
        } else {
            Runtime.getRuntime().exec(arrayOf("svc", "nfc", "disable")).waitFor()
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_BEAM)
        }
    }

    // ============================================
    // Airplane Mode Control
    // ============================================

    /**
     * Enable or disable airplane mode.
     *
     * Uses DISALLOW_AIRPLANE_MODE restriction (Android 9+).
     */
    fun setAirplaneModeEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isDeviceOwner()) { "Airplane mode control requires Device Owner" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Set airplane mode state
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (enabled) 1 else 0
            )

            // Restrict changes if disabled
            if (!enabled) {
                devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE)
            } else {
                // Don't restrict if enabled - let it stay on
            }
        }
    }

    // ============================================
    // Hardware Status
    // ============================================

    /**
     * Get current hardware status
     */
    fun getHardwareStatus(): HardwareStatus {
        return HardwareStatus(
            wifiEnabled = isWifiEnabled(),
            bluetoothEnabled = isBluetoothEnabled(),
            gpsEnabled = isGpsEnabled(),
            usbEnabled = isUsbEnabled()
        )
    }

    // ============================================
    // Policy Enforcement
    // ============================================

    /**
     * Start periodic hardware policy enforcement.
     *
     * The enforcement loop runs at the specified interval and ensures
     * hardware states match the policy configuration.
     */
    fun startEnforcement(policy: HardwarePolicy) {
        if (isEnforcing.getAndSet(true)) {
            // Already enforcing, update policy
            currentPolicy = policy
            return
        }

        currentPolicy = policy
        _enforcementState.value = EnforcementState.Running(policy)

        enforcementJob = coroutineScope.launch {
            while (isActive && isEnforcing.get()) {
                try {
                    enforcePolicy(policy)
                    _enforcementState.value = EnforcementState.Running(policy)
                } catch (e: Exception) {
                    _enforcementState.value = EnforcementState.Error(e.message ?: "Enforcement failed")
                }
                delay(policy.enforcementInterval * 1000L)
            }
        }
    }

    /**
     * Stop hardware policy enforcement
     */
    fun stopEnforcement() {
        isEnforcing.set(false)
        enforcementJob?.cancel()
        enforcementJob = null
        currentPolicy = null
        _enforcementState.value = EnforcementState.Idle
    }

    /**
     * Enforce the current policy once
     */
    fun enforceOnce(): Result<Unit> = runCatching {
        currentPolicy?.let { enforcePolicy(it) }
    }

    private fun enforcePolicy(policy: HardwarePolicy) {
        // Only enforce non-null policy values
        policy.wifiEnabled?.let { enabled ->
            if (isWifiEnabled() != enabled) {
                setWifiEnabled(enabled)
            }
        }

        policy.bluetoothEnabled?.let { enabled ->
            if (isBluetoothEnabled() != enabled) {
                setBluetoothEnabled(enabled)
            }
        }

        policy.gpsEnabled?.let { enabled ->
            if (isGpsEnabled() != enabled) {
                setGpsEnabled(enabled)
            }
        }

        policy.usbEnabled?.let { enabled ->
            if (isUsbEnabled() != enabled) {
                setUsbEnabled(enabled)
            }
        }

        policy.mobileDataEnabled?.let { enabled ->
            setMobileDataEnabled(enabled)
        }

        policy.nfcEnabled?.let { enabled ->
            setNfcEnabled(enabled)
        }
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        stopEnforcement()
        coroutineScope.cancel()
    }
}

/**
 * Current hardware status
 */
data class HardwareStatus(
    val wifiEnabled: Boolean,
    val bluetoothEnabled: Boolean,
    val gpsEnabled: Boolean,
    val usbEnabled: Boolean
)

/**
 * Enforcement loop state
 */
sealed class EnforcementState {
    object Idle : EnforcementState()
    data class Running(val policy: HardwarePolicy) : EnforcementState()
    data class Error(val message: String) : EnforcementState()
}
