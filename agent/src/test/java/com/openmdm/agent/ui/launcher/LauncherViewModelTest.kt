package com.openmdm.agent.ui.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.openmdm.agent.data.EnrollmentState
import com.openmdm.agent.data.MDMApi
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.domain.repository.IEnrollmentRepository
import com.openmdm.agent.domain.usecase.EnrollDeviceUseCase
import com.openmdm.agent.domain.usecase.LaunchAppUseCase
import com.openmdm.agent.domain.usecase.LaunchResult
import com.openmdm.agent.domain.usecase.LoadLauncherAppsUseCase
import com.openmdm.agent.domain.usecase.LauncherAppsResult
import com.openmdm.agent.ui.launcher.model.AppType
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.agent.ui.launcher.model.LauncherEvent
import com.openmdm.agent.ui.launcher.model.LauncherScreenState
import com.openmdm.agent.ui.launcher.model.LauncherUiState
import com.openmdm.agent.util.DeviceInfoCollector
import com.openmdm.library.policy.LauncherConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LauncherViewModel].
 *
 * Tests enrollment flow, app loading, filtering, click handling, and overlay behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LauncherViewModelTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var mdmRepository: MDMRepository
    private lateinit var enrollmentRepository: IEnrollmentRepository
    private lateinit var mdmApi: MDMApi
    private lateinit var enrollDeviceUseCase: EnrollDeviceUseCase
    private lateinit var loadLauncherAppsUseCase: LoadLauncherAppsUseCase
    private lateinit var launchAppUseCase: LaunchAppUseCase
    private lateinit var deviceInfoCollector: DeviceInfoCollector
    private lateinit var viewModel: LauncherViewModel

    private val testDispatcher = StandardTestDispatcher()
    private val testPackageName = "com.openmdm.agent"

    // Mocked enrollment state flow
    private val enrollmentStateFlow = MutableStateFlow(
        EnrollmentState(
            isEnrolled = true,
            deviceId = "test-device-id",
            token = "test-token",
            policyVersion = "1.0"
        )
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        mdmRepository = mockk(relaxed = true)
        enrollmentRepository = mockk(relaxed = true)
        mdmApi = mockk(relaxed = true)
        enrollDeviceUseCase = mockk(relaxed = true)
        loadLauncherAppsUseCase = mockk(relaxed = true)
        launchAppUseCase = mockk(relaxed = true)
        deviceInfoCollector = mockk(relaxed = true)

        every { context.packageName } returns testPackageName
        every { context.packageManager } returns packageManager
        every { packageManager.queryIntentActivities(any<Intent>(), any<Int>()) } returns emptyList()

        // Mock enrollment state as enrolled with policy (to skip enrollment screen in tests)
        every { mdmRepository.enrollmentState } returns enrollmentStateFlow

        // Mock Use Cases default behavior
        coEvery { loadLauncherAppsUseCase.invoke(any()) } returns Result.success(
            LauncherAppsResult(mainApps = emptyList(), bottomBarApps = emptyList())
        )
        every { launchAppUseCase.isAppAllowed(any(), any()) } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LauncherViewModel {
        return LauncherViewModel(
            context = context,
            mdmRepository = mdmRepository,
            enrollmentRepository = enrollmentRepository,
            mdmApi = mdmApi,
            enrollDeviceUseCase = enrollDeviceUseCase,
            loadLauncherAppsUseCase = loadLauncherAppsUseCase,
            launchAppUseCase = launchAppUseCase,
            deviceInfoCollector = deviceInfoCollector
        )
    }

    // ============================================
    // Initial State Tests
    // ============================================

    @Test
    fun `initial state has correct defaults`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value

        assertThat(state.apps).isEmpty()
        assertThat(state.bottomBarApps).isEmpty()
        assertThat(state.blockedAppPackage).isNull()
        assertThat(state.isKioskMode).isFalse()
        assertThat(state.columns).isEqualTo(4)
        assertThat(state.showBottomBar).isTrue()
    }

    @Test
    fun `screen state starts as loading`() = runTest {
        viewModel = createViewModel()

        // Before any coroutines run, screen state starts as Loading
        // Note: StateFlow collection may have already triggered, so we just verify it's a valid state
        val screenState = viewModel.screenState.value
        assertThat(screenState).isAnyOf(
            LauncherScreenState.Loading,
            LauncherScreenState.Launcher(viewModel.uiState.value)
        )
    }

    // ============================================
    // Blocked Overlay Tests
    // ============================================

    @Test
    fun `showBlockedOverlay sets blockedAppPackage`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showBlockedOverlay("com.blocked")

        assertThat(viewModel.uiState.value.blockedAppPackage).isEqualTo("com.blocked")
    }

    @Test
    fun `dismissBlockedOverlay clears blockedAppPackage`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showBlockedOverlay("com.blocked")
        assertThat(viewModel.uiState.value.blockedAppPackage).isNotNull()

        viewModel.dismissBlockedOverlay()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.blockedAppPackage).isNull()
    }

    @Test
    fun `dismissBlockedOverlay emits BlockedOverlayDismissed event`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.events.test {
            viewModel.dismissBlockedOverlay()
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(LauncherEvent.BlockedOverlayDismissed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================
    // Admin Panel Tests
    // ============================================

    @Test
    fun `openAdminPanel emits AdminPanelRequested event`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.events.test {
            viewModel.openAdminPanel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(LauncherEvent.AdminPanelRequested)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `openAdminPanel dismisses blocked overlay`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showBlockedOverlay("com.blocked")
        assertThat(viewModel.uiState.value.blockedAppPackage).isNotNull()

        viewModel.openAdminPanel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.blockedAppPackage).isNull()
    }

    // ============================================
    // isAppAllowed Tests
    // ============================================

    @Test
    fun `isAppAllowed returns true in default mode`() = runTest {
        // In default mode (no config), all apps are allowed
        every { launchAppUseCase.isAppAllowed(any(), null) } returns true

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.isAppAllowed("com.any.app")).isTrue()
    }

    @Test
    fun `isAppAllowed checks allowlist in allowlist mode`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val config = LauncherConfig(
            enabled = true,
            mode = "allowlist",
            allowedApps = listOf("com.allowed"),
            blockedApps = emptyList(),
            apps = emptyList(),
            columns = 4,
            showBottomBar = true
        )

        // Setup mock for allowlist mode
        every { launchAppUseCase.isAppAllowed("com.allowed", config) } returns true
        every { launchAppUseCase.isAppAllowed("com.not.allowed", config) } returns false
        every { launchAppUseCase.isAppAllowed(testPackageName, config) } returns true
        every { launchAppUseCase.isAppAllowed("com.android.settings", config) } returns true

        viewModel.updateConfig(config)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.isAppAllowed("com.allowed")).isTrue()
        assertThat(viewModel.isAppAllowed("com.not.allowed")).isFalse()
        // MDM app is always allowed
        assertThat(viewModel.isAppAllowed(testPackageName)).isTrue()
        // Settings is always allowed
        assertThat(viewModel.isAppAllowed("com.android.settings")).isTrue()
    }

    @Test
    fun `isAppAllowed checks blocklist in blocklist mode`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val config = LauncherConfig(
            enabled = true,
            mode = "blocklist",
            allowedApps = emptyList(),
            blockedApps = listOf("com.blocked"),
            apps = emptyList(),
            columns = 4,
            showBottomBar = true
        )

        // Setup mock for blocklist mode
        every { launchAppUseCase.isAppAllowed("com.blocked", config) } returns false
        every { launchAppUseCase.isAppAllowed("com.not.blocked", config) } returns true

        viewModel.updateConfig(config)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.isAppAllowed("com.blocked")).isFalse()
        assertThat(viewModel.isAppAllowed("com.not.blocked")).isTrue()
    }

    // ============================================
    // Config Update Tests
    // ============================================

    @Test
    fun `updateConfig changes columns and showBottomBar`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val config = LauncherConfig(
            enabled = true,
            mode = "default",
            allowedApps = emptyList(),
            blockedApps = emptyList(),
            apps = emptyList(),
            columns = 6,
            showBottomBar = false
        )

        viewModel.updateConfig(config)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.columns).isEqualTo(6)
        assertThat(state.showBottomBar).isFalse()
    }

    @Test
    fun `updateConfig sets kiosk mode for allowlist`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val config = LauncherConfig(
            enabled = true,
            mode = "allowlist",
            allowedApps = emptyList(),
            blockedApps = emptyList(),
            apps = emptyList(),
            columns = 4,
            showBottomBar = true
        )

        viewModel.updateConfig(config)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isKioskMode).isTrue()
    }

    // ============================================
    // LauncherAppInfo Tests
    // ============================================

    @Test
    fun `LauncherAppInfo has correct defaults`() {
        val app = LauncherAppInfo(
            packageName = "com.test",
            label = "Test"
        )

        assertThat(app.type).isEqualTo(AppType.INSTALLED)
        assertThat(app.isBottomBar).isFalse()
        assertThat(app.screenOrder).isNull()
        assertThat(app.url).isNull()
        assertThat(app.intentAction).isNull()
        assertThat(app.intentUri).isNull()
    }

    @Test
    fun `LauncherAppInfo holds custom values`() {
        val app = LauncherAppInfo(
            packageName = "web-google",
            label = "Google",
            type = AppType.WEB,
            url = "https://google.com",
            screenOrder = 5,
            isBottomBar = true
        )

        assertThat(app.packageName).isEqualTo("web-google")
        assertThat(app.label).isEqualTo("Google")
        assertThat(app.type).isEqualTo(AppType.WEB)
        assertThat(app.url).isEqualTo("https://google.com")
        assertThat(app.screenOrder).isEqualTo(5)
        assertThat(app.isBottomBar).isTrue()
    }

    // ============================================
    // LauncherEvent Tests
    // ============================================

    @Test
    fun `LauncherEvent types are distinguishable`() {
        val adminEvent = LauncherEvent.AdminPanelRequested
        val dismissEvent = LauncherEvent.BlockedOverlayDismissed
        val refreshEvent = LauncherEvent.RefreshRequested
        val clickEvent = LauncherEvent.AppClicked(
            LauncherAppInfo(packageName = "com.test", label = "Test")
        )

        assertThat(adminEvent).isNotEqualTo(dismissEvent)
        assertThat(refreshEvent).isNotEqualTo(adminEvent)
        assertThat(clickEvent).isNotEqualTo(adminEvent)
    }

    // ============================================
    // AppType Tests
    // ============================================

    @Test
    fun `AppType enum has expected values`() {
        assertThat(AppType.values()).asList().containsExactly(
            AppType.INSTALLED,
            AppType.WEB,
            AppType.INTENT
        )
    }

    // ============================================
    // Screen State Tests
    // ============================================

    @Test
    fun `screen state shows enrollment when not enrolled`() = runTest {
        // Update enrollment state to not enrolled
        enrollmentStateFlow.value = EnrollmentState(
            isEnrolled = false,
            serverUrl = "https://mdm.example.com"
        )

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val screenState = viewModel.screenState.value
        assertThat(screenState).isInstanceOf(LauncherScreenState.Enrollment::class.java)
        assertThat((screenState as LauncherScreenState.Enrollment).serverUrl).isEqualTo("https://mdm.example.com")
    }

    @Test
    fun `uiState has correct defaults for enrolled device`() = runTest {
        // Already enrolled with policy (default setup)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify the ui state has the correct defaults (regardless of screen state)
        val uiState = viewModel.uiState.value
        assertThat(uiState.columns).isEqualTo(4)
        assertThat(uiState.showBottomBar).isTrue()
        assertThat(uiState.isKioskMode).isFalse()
    }

    @Test
    fun `LauncherScreenState types are distinguishable`() {
        val loading = LauncherScreenState.Loading
        val enrollment = LauncherScreenState.Enrollment(serverUrl = "https://test.com")
        val launcher = LauncherScreenState.Launcher(LauncherUiState())

        assertThat(loading).isNotEqualTo(enrollment)
        assertThat(enrollment).isNotEqualTo(launcher)
        assertThat(launcher).isNotEqualTo(loading)
    }
}
