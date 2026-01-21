package com.openmdm.agent.ui.launcher

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmdm.agent.data.EnrollmentState
import com.openmdm.agent.data.HeartbeatRequest
import com.openmdm.agent.data.InstalledAppData
import com.openmdm.agent.data.LocationData
import com.openmdm.agent.data.MDMApi
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.domain.repository.IEnrollmentRepository
import com.openmdm.agent.domain.usecase.EnrollDeviceUseCase
import com.openmdm.agent.domain.usecase.LaunchAppUseCase
import com.openmdm.agent.domain.usecase.LaunchResult
import com.openmdm.agent.domain.usecase.LoadLauncherAppsUseCase
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.agent.ui.launcher.model.LauncherEvent
import com.openmdm.agent.ui.launcher.model.LauncherScreenState
import com.openmdm.agent.ui.launcher.model.LauncherUiState
import com.openmdm.agent.util.DeviceInfoCollector
import com.openmdm.library.policy.LauncherConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * ViewModel for the MDM Launcher screen.
 *
 * Manages enrollment, app loading, filtering based on policy, and app launching.
 * Implements an enrollment-first flow: device must be enrolled before showing launcher content.
 *
 * Uses Clean Architecture with Use Cases:
 * - [EnrollDeviceUseCase] for enrollment logic
 * - [LoadLauncherAppsUseCase] for loading and filtering apps
 * - [LaunchAppUseCase] for launching apps with policy checks
 */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mdmRepository: MDMRepository,
    private val enrollmentRepository: IEnrollmentRepository,
    private val mdmApi: MDMApi,
    private val enrollDeviceUseCase: EnrollDeviceUseCase,
    private val loadLauncherAppsUseCase: LoadLauncherAppsUseCase,
    private val launchAppUseCase: LaunchAppUseCase,
    private val deviceInfoCollector: DeviceInfoCollector
) : ViewModel() {

    /** Screen state for enrollment-first flow */
    private val _screenState = MutableStateFlow<LauncherScreenState>(LauncherScreenState.Loading)
    val screenState: StateFlow<LauncherScreenState> = _screenState.asStateFlow()

    /** Internal UI state for launcher content (backwards compatibility) */
    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LauncherEvent>()
    val events = _events.asSharedFlow()

    private var launcherConfig: LauncherConfig? = null

    init {
        observeEnrollmentState()
    }

    /**
     * Observe enrollment state and update screen state accordingly.
     */
    private fun observeEnrollmentState() {
        viewModelScope.launch {
            mdmRepository.enrollmentState.collect { state ->
                when {
                    !state.isEnrolled -> {
                        // Not enrolled - show enrollment screen
                        _screenState.value = LauncherScreenState.Enrollment(
                            serverUrl = state.serverUrl
                        )
                    }
                    state.policyVersion == null -> {
                        // Enrolled but no policy yet - fetch it
                        _screenState.value = LauncherScreenState.Loading
                        fetchInitialPolicy(state)
                    }
                    else -> {
                        // Enrolled with policy - show launcher
                        loadApps()
                    }
                }
            }
        }
    }

    /**
     * Enroll the device with the given device code and server URL.
     */
    fun enroll(deviceCode: String, serverUrl: String) {
        viewModelScope.launch {
            // Update state to show enrolling
            _screenState.update {
                (it as? LauncherScreenState.Enrollment)?.copy(
                    isEnrolling = true,
                    errorMessage = null
                ) ?: it
            }

            try {
                val result = withContext(Dispatchers.IO) {
                    enrollDeviceUseCase(deviceCode, serverUrl)
                }

                result.fold(
                    onSuccess = { /* State will update via Flow observation */ },
                    onFailure = { error ->
                        _screenState.update {
                            (it as? LauncherScreenState.Enrollment)?.copy(
                                isEnrolling = false,
                                errorMessage = error.message ?: "Enrollment failed"
                            ) ?: it
                        }
                    }
                )
            } catch (e: Exception) {
                _screenState.update {
                    (it as? LauncherScreenState.Enrollment)?.copy(
                        isEnrolling = false,
                        errorMessage = e.message ?: "Enrollment failed"
                    ) ?: it
                }
            }
        }
    }

    private suspend fun fetchInitialPolicy(state: EnrollmentState) {
        try {
            val heartbeatData = deviceInfoCollector.collectHeartbeatData()
            val timestamp = generateTimestamp()

            val request = HeartbeatRequest(
                deviceId = state.deviceId ?: return,
                timestamp = timestamp,
                batteryLevel = heartbeatData.batteryLevel,
                isCharging = heartbeatData.isCharging,
                batteryHealth = heartbeatData.batteryHealth,
                storageUsed = heartbeatData.storageUsed,
                storageTotal = heartbeatData.storageTotal,
                memoryUsed = heartbeatData.memoryUsed,
                memoryTotal = heartbeatData.memoryTotal,
                networkType = heartbeatData.networkType,
                networkName = heartbeatData.networkName,
                signalStrength = heartbeatData.signalStrength,
                ipAddress = heartbeatData.ipAddress,
                location = heartbeatData.location?.let { LocationData(it.latitude, it.longitude, it.accuracy) },
                installedApps = heartbeatData.installedApps.map { InstalledAppData(it.packageName, it.version, it.versionCode) },
                runningApps = heartbeatData.runningApps,
                isRooted = heartbeatData.isRooted,
                isEncrypted = heartbeatData.isEncrypted,
                screenLockEnabled = heartbeatData.screenLockEnabled,
                agentVersion = heartbeatData.agentVersion,
                policyVersion = state.policyVersion
            )

            val response = mdmApi.heartbeat("Bearer ${state.token}", request)
            if (response.isSuccessful) {
                response.body()?.policyUpdate?.let { policy ->
                    enrollmentRepository.updatePolicyVersion(policy.version ?: "1")
                }
                // If no policy update but successful, still allow launcher
                if (response.body()?.policyUpdate == null) {
                    enrollmentRepository.updatePolicyVersion("default")
                }
            } else {
                // Allow launcher to show even if heartbeat fails
                enrollmentRepository.updatePolicyVersion("default")
            }
        } catch (e: Exception) {
            // Allow launcher to show even if heartbeat fails
            enrollmentRepository.updatePolicyVersion("default")
        }
    }

    private fun generateTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /**
     * Load and filter apps based on policy.
     */
    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                val result = withContext(Dispatchers.IO) {
                    loadLauncherAppsUseCase(launcherConfig)
                }

                result.fold(
                    onSuccess = { appsResult ->
                        val newState = _uiState.value.copy(
                            apps = appsResult.mainApps,
                            bottomBarApps = appsResult.bottomBarApps,
                            isLoading = false,
                            columns = launcherConfig?.columns ?: 4,
                            showBottomBar = launcherConfig?.showBottomBar ?: true
                        )
                        _uiState.value = newState

                        // Update screen state to show launcher
                        _screenState.value = LauncherScreenState.Launcher(newState)
                    },
                    onFailure = { error ->
                        val newState = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Failed to load apps"
                        )
                        _uiState.value = newState

                        // Still show launcher even with error
                        _screenState.value = LauncherScreenState.Launcher(newState)
                    }
                )
            } catch (e: Exception) {
                val newState = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Failed to load apps"
                )
                _uiState.value = newState

                // Still show launcher even with error
                _screenState.value = LauncherScreenState.Launcher(newState)
            }
        }
    }

    /**
     * Update the launcher configuration from policy.
     */
    fun updateConfig(config: LauncherConfig) {
        launcherConfig = config
        val newState = _uiState.value.copy(
            isKioskMode = config.mode == "allowlist",
            columns = config.columns,
            showBottomBar = config.showBottomBar
        )
        _uiState.value = newState

        // Sync screen state if in launcher mode
        val currentScreenState = _screenState.value
        if (currentScreenState is LauncherScreenState.Launcher) {
            _screenState.value = LauncherScreenState.Launcher(newState)
        }

        loadApps()
    }

    /**
     * Handle app click - launch the app or show blocked overlay.
     */
    fun onAppClick(app: LauncherAppInfo) {
        viewModelScope.launch {
            when (val result = launchAppUseCase(app, launcherConfig)) {
                is LaunchResult.Success -> {
                    // App launched successfully
                }
                is LaunchResult.Blocked -> {
                    showBlockedOverlay(result.packageName)
                }
                is LaunchResult.Failed -> {
                    _uiState.update { it.copy(errorMessage = result.errorMessage) }
                }
            }
        }
    }

    /**
     * Show blocked app overlay.
     */
    fun showBlockedOverlay(packageName: String) {
        val newState = _uiState.value.copy(blockedAppPackage = packageName)
        _uiState.value = newState

        // Sync screen state
        val currentScreenState = _screenState.value
        if (currentScreenState is LauncherScreenState.Launcher) {
            _screenState.value = LauncherScreenState.Launcher(newState)
        }
    }

    /**
     * Dismiss blocked app overlay.
     */
    fun dismissBlockedOverlay() {
        val newState = _uiState.value.copy(blockedAppPackage = null)
        _uiState.value = newState

        // Sync screen state
        val currentScreenState = _screenState.value
        if (currentScreenState is LauncherScreenState.Launcher) {
            _screenState.value = LauncherScreenState.Launcher(newState)
        }

        viewModelScope.launch {
            _events.emit(LauncherEvent.BlockedOverlayDismissed)
        }
    }

    /**
     * Open the admin panel (MainActivity).
     */
    fun openAdminPanel() {
        viewModelScope.launch {
            _events.emit(LauncherEvent.AdminPanelRequested)
        }
        dismissBlockedOverlay()
    }

    /**
     * Check if an app is allowed to launch.
     * Delegates to [LaunchAppUseCase] for policy checking.
     */
    fun isAppAllowed(packageName: String): Boolean {
        return launchAppUseCase.isAppAllowed(packageName, launcherConfig)
    }
}
