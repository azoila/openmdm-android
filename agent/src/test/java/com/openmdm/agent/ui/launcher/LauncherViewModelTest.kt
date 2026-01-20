package com.openmdm.agent.ui.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.ui.launcher.model.AppType
import com.openmdm.agent.ui.launcher.model.LauncherAppInfo
import com.openmdm.agent.ui.launcher.model.LauncherEvent
import com.openmdm.agent.util.DeviceOwnerManager
import com.openmdm.library.policy.LauncherConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Tests app loading, filtering, click handling, and overlay behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LauncherViewModelTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var mdmRepository: MDMRepository
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var viewModel: LauncherViewModel

    private val testDispatcher = StandardTestDispatcher()
    private val testPackageName = "com.openmdm.agent"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        mdmRepository = mockk(relaxed = true)
        deviceOwnerManager = mockk(relaxed = true)

        every { context.packageName } returns testPackageName
        every { context.packageManager } returns packageManager
        every { packageManager.queryIntentActivities(any<Intent>(), any<Int>()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LauncherViewModel {
        return LauncherViewModel(context, mdmRepository, deviceOwnerManager)
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
    fun `initial state is loading`() = runTest {
        viewModel = createViewModel()

        // Initially loading
        assertThat(viewModel.uiState.value.isLoading).isTrue()

        testDispatcher.scheduler.advanceUntilIdle()

        // After loading completes
        assertThat(viewModel.uiState.value.isLoading).isFalse()
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
}
