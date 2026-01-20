package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LauncherManager].
 *
 * Tests app visibility management, policy application, and launcher preferences.
 * Uses mocked DevicePolicyManager since real DPM requires Device Owner privileges.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LauncherManagerTest {

    private lateinit var context: Context
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var packageManager: PackageManager
    private lateinit var adminComponent: ComponentName
    private lateinit var launcherManager: LauncherManager

    private val testPackageName = "com.openmdm.agent"
    private val targetPackage = "com.example.target"
    private val blockedPackage = "com.example.blocked"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        devicePolicyManager = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        adminComponent = ComponentName(testPackageName, "TestReceiver")

        every { context.packageName } returns testPackageName
        every { context.getSystemService(Context.DEVICE_POLICY_SERVICE) } returns devicePolicyManager
        every { context.packageManager } returns packageManager

        // Default to not device owner
        every { devicePolicyManager.isDeviceOwnerApp(any()) } returns false

        launcherManager = LauncherManager.create(context, adminComponent)
    }

    // ============================================
    // Factory Method Tests
    // ============================================

    @Test
    fun `create returns LauncherManager instance`() {
        val manager = LauncherManager.create(context, adminComponent)
        assertThat(manager).isNotNull()
    }

    // ============================================
    // Hide App Tests
    // ============================================

    @Test
    fun `hideApp succeeds when device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns true
        every { devicePolicyManager.setApplicationHidden(adminComponent, targetPackage, true) } returns true

        val result = launcherManager.hideApp(targetPackage)

        assertThat(result.isSuccess).isTrue()
        verify { devicePolicyManager.setApplicationHidden(adminComponent, targetPackage, true) }
    }

    @Test
    fun `hideApp fails when not device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns false

        val result = launcherManager.hideApp(targetPackage)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Device Owner")
    }

    @Test
    fun `hideApp fails when trying to hide MDM app`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns true

        val result = launcherManager.hideApp(testPackageName)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Cannot hide the MDM app")
    }

    @Test
    fun `hideApp fails when DPM returns false`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns true
        every { devicePolicyManager.setApplicationHidden(adminComponent, targetPackage, true) } returns false

        val result = launcherManager.hideApp(targetPackage)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to hide app")
    }

    // ============================================
    // Show App Tests
    // ============================================

    @Test
    fun `showApp succeeds when device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns true
        every { devicePolicyManager.setApplicationHidden(adminComponent, targetPackage, false) } returns true

        val result = launcherManager.showApp(targetPackage)

        assertThat(result.isSuccess).isTrue()
        verify { devicePolicyManager.setApplicationHidden(adminComponent, targetPackage, false) }
    }

    @Test
    fun `showApp fails when not device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns false

        val result = launcherManager.showApp(targetPackage)

        assertThat(result.isFailure).isTrue()
    }

    // ============================================
    // isAppHidden Tests
    // ============================================

    @Test
    fun `isAppHidden returns true for hidden app`() {
        every { devicePolicyManager.isApplicationHidden(adminComponent, targetPackage) } returns true

        val result = launcherManager.isAppHidden(targetPackage)

        assertThat(result).isTrue()
    }

    @Test
    fun `isAppHidden returns false for visible app`() {
        every { devicePolicyManager.isApplicationHidden(adminComponent, targetPackage) } returns false

        val result = launcherManager.isAppHidden(targetPackage)

        assertThat(result).isFalse()
    }

    @Test
    fun `isAppHidden returns false on security exception`() {
        every { devicePolicyManager.isApplicationHidden(adminComponent, targetPackage) } throws SecurityException()

        val result = launcherManager.isAppHidden(targetPackage)

        assertThat(result).isFalse()
    }

    // ============================================
    // Suspend Apps Tests
    // ============================================

    @Test
    fun `suspendApps fails when not device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns false

        val result = launcherManager.suspendApps(listOf(targetPackage))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `suspendApps succeeds when device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns true
        every { devicePolicyManager.setPackagesSuspended(adminComponent, arrayOf(targetPackage), true) } returns emptyArray()

        val result = launcherManager.suspendApps(listOf(targetPackage))

        assertThat(result.isSuccess).isTrue()
    }

    // ============================================
    // isAppSuspended Tests
    // ============================================

    @Test
    fun `isAppSuspended returns false on exception`() {
        every { devicePolicyManager.isPackageSuspended(adminComponent, targetPackage) } throws SecurityException()

        val result = launcherManager.isAppSuspended(targetPackage)

        assertThat(result).isFalse()
    }

    @Test
    fun `isAppSuspended returns true when suspended`() {
        every { devicePolicyManager.isPackageSuspended(adminComponent, targetPackage) } returns true

        val result = launcherManager.isAppSuspended(targetPackage)

        assertThat(result).isTrue()
    }

    // ============================================
    // Visibility Policy Tests
    // ============================================

    @Test
    fun `applyVisibilityPolicy fails when not device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns false

        val result = launcherManager.applyVisibilityPolicy(
            allowedApps = listOf(targetPackage),
            blockedApps = emptyList(),
            mode = VisibilityMode.ALLOWLIST
        )

        assertThat(result.isFailure).isTrue()
    }

    // ============================================
    // Default Launcher Tests
    // ============================================

    @Test
    fun `setAsDefaultLauncher succeeds when device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns true
        val launcherActivity = ComponentName(testPackageName, "LauncherActivity")

        val result = launcherManager.setAsDefaultLauncher(launcherActivity)

        assertThat(result.isSuccess).isTrue()
        verify { devicePolicyManager.addPersistentPreferredActivity(adminComponent, any(), launcherActivity) }
    }

    @Test
    fun `setAsDefaultLauncher fails when not device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns false
        val launcherActivity = ComponentName(testPackageName, "LauncherActivity")

        val result = launcherManager.setAsDefaultLauncher(launcherActivity)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `clearDefaultLauncher succeeds when device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns true

        val result = launcherManager.clearDefaultLauncher()

        assertThat(result.isSuccess).isTrue()
        verify { devicePolicyManager.clearPackagePersistentPreferredActivities(adminComponent, testPackageName) }
    }

    @Test
    fun `clearDefaultLauncher fails when not device owner`() {
        every { devicePolicyManager.isDeviceOwnerApp(testPackageName) } returns false

        val result = launcherManager.clearDefaultLauncher()

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `getDefaultLauncher returns package name`() {
        val mockActivityInfo = ActivityInfo().apply {
            packageName = "com.android.launcher3"
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = mockActivityInfo
        }
        every { packageManager.resolveActivity(any<Intent>(), PackageManager.MATCH_DEFAULT_ONLY) } returns resolveInfo

        val result = launcherManager.getDefaultLauncher()

        assertThat(result).isEqualTo("com.android.launcher3")
    }

    @Test
    fun `getDefaultLauncher returns null when no launcher`() {
        every { packageManager.resolveActivity(any<Intent>(), PackageManager.MATCH_DEFAULT_ONLY) } returns null

        val result = launcherManager.getDefaultLauncher()

        assertThat(result).isNull()
    }

    // ============================================
    // getLaunchableApps Tests
    // ============================================

    @Test
    fun `getLaunchableApps returns empty list when no apps installed`() {
        every { packageManager.queryIntentActivities(any<Intent>(), any<Int>()) } returns emptyList()

        val apps = launcherManager.getLaunchableApps(includeHidden = false)

        assertThat(apps).isEmpty()
    }

    // ============================================
    // getFilteredApps Tests
    // ============================================

    @Test
    fun `getFilteredApps returns empty list when no apps`() {
        every { packageManager.queryIntentActivities(any<Intent>(), any<Int>()) } returns emptyList()

        val apps = launcherManager.getFilteredApps(
            allowedApps = emptyList(),
            blockedApps = emptyList()
        )

        assertThat(apps).isEmpty()
    }

    // ============================================
    // VisibilityMode Tests
    // ============================================

    @Test
    fun `VisibilityMode enum has expected values`() {
        assertThat(VisibilityMode.values()).asList().containsExactly(
            VisibilityMode.DEFAULT,
            VisibilityMode.ALLOWLIST,
            VisibilityMode.BLOCKLIST
        )
    }

    // ============================================
    // LaunchableApp Tests
    // ============================================

    @Test
    fun `LaunchableApp data class holds correct values`() {
        val icon = mockk<Drawable>()
        val app = LaunchableApp(
            packageName = "com.test.app",
            label = "Test App",
            icon = icon,
            isSystemApp = true,
            isHidden = false,
            isSuspended = true
        )

        assertThat(app.packageName).isEqualTo("com.test.app")
        assertThat(app.label).isEqualTo("Test App")
        assertThat(app.icon).isEqualTo(icon)
        assertThat(app.isSystemApp).isTrue()
        assertThat(app.isHidden).isFalse()
        assertThat(app.isSuspended).isTrue()
    }

    @Test
    fun `LaunchableApp has sensible defaults`() {
        val app = LaunchableApp(
            packageName = "com.test.app",
            label = "Test",
            icon = null,
            isSystemApp = false
        )

        assertThat(app.isHidden).isFalse()
        assertThat(app.isSuspended).isFalse()
    }
}
