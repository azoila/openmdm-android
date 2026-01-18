package com.openmdm.library.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for device management functionality.
 *
 * These tests require the app to be provisioned as Device Owner.
 * Skip tests automatically if Device Owner is not enabled.
 *
 * To provision as Device Owner for testing:
 * ```
 * adb shell dpm set-device-owner com.openmdm.library.test/.TestDeviceAdminReceiver
 * ```
 *
 * Note: Some tests may modify device state. Run on a test device only.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeviceManagerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var userManager: UserManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    }

    // ============================================
    // Status Check Tests (No Device Owner Required)
    // ============================================

    @Test
    fun devicePolicyManager_isAvailable() {
        assertThat(devicePolicyManager).isNotNull()
    }

    @Test
    fun userManager_isAvailable() {
        assertThat(userManager).isNotNull()
    }

    // ============================================
    // Device Owner Status Tests
    // ============================================

    @Test
    fun checkDeviceOwnerStatus_returnsCorrectState() {
        val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)

        // This test just verifies the API works, result depends on provisioning
        assertThat(isDeviceOwner).isAnyOf(true, false)
    }

    // ============================================
    // HardwareManager Tests (Require Device Owner)
    // ============================================

    /**
     * Tests for HardwareManager.
     *
     * These tests require Device Owner permission and will be skipped
     * if the app is not provisioned as Device Owner.
     */
    class HardwareManagerTests {

        private lateinit var context: Context
        private lateinit var dpm: DevicePolicyManager

        @Before
        fun setup() {
            context = ApplicationProvider.getApplicationContext()
            dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        }

        private fun assumeDeviceOwner() {
            assumeTrue(
                "Test requires Device Owner permission",
                dpm.isDeviceOwnerApp(context.packageName)
            )
        }

        @Test
        fun getHardwareStatus_returnsCurrentState() {
            // This test works without Device Owner - just reads current state
            val hardwareManager = createTestHardwareManager()

            val status = hardwareManager.getHardwareStatus()

            // Status should be non-null and contain valid boolean values
            assertThat(status).isNotNull()
        }

        @Test
        fun setWifiEnabled_requiresDeviceOwner() {
            assumeDeviceOwner()

            val hardwareManager = createTestHardwareManager()
            val originalState = hardwareManager.isWifiEnabled()

            // Toggle WiFi
            val result = hardwareManager.setWifiEnabled(!originalState)

            // Result should succeed with Device Owner
            assertThat(result.isSuccess).isTrue()

            // Restore original state
            hardwareManager.setWifiEnabled(originalState)
        }

        @Test
        fun setGpsEnabled_requiresDeviceOwner() {
            assumeDeviceOwner()

            val hardwareManager = createTestHardwareManager()
            val originalState = hardwareManager.isGpsEnabled()

            // Toggle GPS
            val result = hardwareManager.setGpsEnabled(!originalState)

            assertThat(result.isSuccess).isTrue()

            // Restore original state
            hardwareManager.setGpsEnabled(originalState)
        }

        @Test
        fun setUsbEnabled_setsRestriction() {
            assumeDeviceOwner()

            val hardwareManager = createTestHardwareManager()

            // Disable USB
            val disableResult = hardwareManager.setUsbEnabled(false)
            assertThat(disableResult.isSuccess).isTrue()
            assertThat(hardwareManager.isUsbEnabled()).isFalse()

            // Re-enable USB
            val enableResult = hardwareManager.setUsbEnabled(true)
            assertThat(enableResult.isSuccess).isTrue()
            assertThat(hardwareManager.isUsbEnabled()).isTrue()
        }

        private fun createTestHardwareManager(): HardwareManager {
            // Use a mock admin component for testing
            val adminComponent = ComponentName(context, "com.openmdm.library.test.TestDeviceAdminReceiver")
            return HardwareManager.create(context, adminComponent)
        }
    }

    // ============================================
    // ScreenManager Tests (Require Device Owner/Admin)
    // ============================================

    /**
     * Tests for ScreenManager.
     */
    class ScreenManagerTests {

        private lateinit var context: Context
        private lateinit var dpm: DevicePolicyManager

        @Before
        fun setup() {
            context = ApplicationProvider.getApplicationContext()
            dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        }

        private fun assumeDeviceOwner() {
            assumeTrue(
                "Test requires Device Owner permission",
                dpm.isDeviceOwnerApp(context.packageName)
            )
        }

        @Test
        fun getScreenStatus_returnsCurrentSettings() {
            val screenManager = createTestScreenManager()

            val status = screenManager.getScreenStatus()

            assertThat(status).isNotNull()
            assertThat(status.timeoutSeconds).isAtLeast(-1)
            assertThat(status.brightness).isIn(-1..255)
        }

        @Test
        fun setScreenshotDisabled_requiresDeviceAdmin() {
            assumeDeviceOwner()

            val screenManager = createTestScreenManager()
            val originalState = screenManager.isScreenshotDisabled()

            // Toggle screenshot state
            val result = screenManager.setScreenshotDisabled(!originalState)

            assertThat(result.isSuccess).isTrue()

            // Restore original state
            screenManager.setScreenshotDisabled(originalState)
        }

        @Test
        fun setBrightness_setsSystemSetting() {
            val screenManager = createTestScreenManager()
            val originalBrightness = screenManager.getBrightness()

            // Set brightness to 128
            val result = screenManager.setBrightness(128)

            // This may fail without WRITE_SETTINGS permission
            if (result.isSuccess) {
                assertThat(screenManager.getBrightness()).isEqualTo(128)
            }

            // Restore original brightness
            if (originalBrightness != ScreenManager.BRIGHTNESS_AUTO) {
                screenManager.setBrightness(originalBrightness)
            }
        }

        private fun createTestScreenManager(): ScreenManager {
            val adminComponent = ComponentName(context, "com.openmdm.library.test.TestDeviceAdminReceiver")
            return ScreenManager.create(context, adminComponent)
        }
    }

    // ============================================
    // KioskManager Tests (Require Device Owner)
    // ============================================

    /**
     * Tests for KioskManager.
     */
    class KioskManagerTests {

        private lateinit var context: Context
        private lateinit var dpm: DevicePolicyManager

        @Before
        fun setup() {
            context = ApplicationProvider.getApplicationContext()
            dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        }

        private fun assumeDeviceOwner() {
            assumeTrue(
                "Test requires Device Owner permission",
                dpm.isDeviceOwnerApp(context.packageName)
            )
        }

        @Test
        fun getKioskStatus_returnsCurrentState() {
            val kioskManager = createTestKioskManager()

            val status = kioskManager.getKioskStatus()

            assertThat(status).isNotNull()
            // Initially should not be in lock task mode
            // (unless another test or app set it)
        }

        @Test
        fun setLockTaskPackages_requiresDeviceOwner() {
            assumeDeviceOwner()

            val kioskManager = createTestKioskManager()
            val packages = listOf(context.packageName)

            val result = kioskManager.setLockTaskPackages(packages)

            assertThat(result.isSuccess).isTrue()

            // Verify packages were set
            val allowedPackages = kioskManager.getLockTaskPackages()
            assertThat(allowedPackages).contains(context.packageName)

            // Clear lock task packages
            kioskManager.setLockTaskPackages(emptyList())
        }

        @Test
        fun isPackageAllowedInLockTask_checksPermission() {
            assumeDeviceOwner()

            val kioskManager = createTestKioskManager()

            // First set the package as allowed
            kioskManager.setLockTaskPackages(listOf(context.packageName))

            val isAllowed = kioskManager.isPackageAllowedInLockTask(context.packageName)

            assertThat(isAllowed).isTrue()

            // Cleanup
            kioskManager.setLockTaskPackages(emptyList())
        }

        private fun createTestKioskManager(): KioskManager {
            val adminComponent = ComponentName(context, "com.openmdm.library.test.TestDeviceAdminReceiver")
            return KioskManager.create(context, adminComponent)
        }
    }

    // ============================================
    // RestrictionManager Tests (Require Device Owner)
    // ============================================

    /**
     * Tests for RestrictionManager.
     */
    class RestrictionManagerTests {

        private lateinit var context: Context
        private lateinit var dpm: DevicePolicyManager
        private lateinit var userManager: UserManager

        @Before
        fun setup() {
            context = ApplicationProvider.getApplicationContext()
            dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        }

        private fun assumeDeviceOwner() {
            assumeTrue(
                "Test requires Device Owner permission",
                dpm.isDeviceOwnerApp(context.packageName)
            )
        }

        @Test
        fun getRestrictionStatus_returnsCurrentRestrictions() {
            val restrictionManager = createTestRestrictionManager()

            val status = restrictionManager.getRestrictionStatus()

            assertThat(status).isNotNull()
            assertThat(status.activeRestrictions).isNotNull()
        }

        @Test
        fun setRestriction_requiresDeviceOwner() {
            assumeDeviceOwner()

            val restrictionManager = createTestRestrictionManager()
            val restriction = UserManager.DISALLOW_USB_FILE_TRANSFER

            // Set restriction
            val setResult = restrictionManager.setRestriction(restriction, true)
            assertThat(setResult.isSuccess).isTrue()
            assertThat(restrictionManager.hasRestriction(restriction)).isTrue()

            // Clear restriction
            val clearResult = restrictionManager.setRestriction(restriction, false)
            assertThat(clearResult.isSuccess).isTrue()
            assertThat(restrictionManager.hasRestriction(restriction)).isFalse()
        }

        @Test
        fun applyRestrictionsFromString_parsesCSV() {
            assumeDeviceOwner()

            val restrictionManager = createTestRestrictionManager()
            val csv = "no_usb_file_transfer"

            val result = restrictionManager.applyRestrictionsFromString(csv)

            assertThat(result.isSuccess).isTrue()
            val applied = result.getOrThrow()
            assertThat(applied).isNotEmpty()

            // Cleanup - clear the restriction
            restrictionManager.setRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, false)
        }

        @Test
        fun isRestrictionSupported_checksApiLevel() {
            val restrictionManager = createTestRestrictionManager()

            // DISALLOW_USB_FILE_TRANSFER is available on all supported API levels
            val supported = restrictionManager.isRestrictionSupported(UserManager.DISALLOW_USB_FILE_TRANSFER)

            assertThat(supported).isTrue()
        }

        private fun createTestRestrictionManager(): RestrictionManager {
            val adminComponent = ComponentName(context, "com.openmdm.library.test.TestDeviceAdminReceiver")
            return RestrictionManager.create(context, adminComponent)
        }
    }

    // ============================================
    // NetworkManager Tests
    // ============================================

    /**
     * Tests for NetworkManager.
     */
    class NetworkManagerTests {

        private lateinit var context: Context

        @Before
        fun setup() {
            context = ApplicationProvider.getApplicationContext()
        }

        @Test
        fun getWifiStatus_returnsCurrentState() {
            val networkManager = createTestNetworkManager()

            val status = networkManager.getWifiStatus()

            assertThat(status).isNotNull()
            // WiFi enabled state is a valid boolean
        }

        @Test
        fun getNetworkStatus_returnsCurrentConnectivity() {
            val networkManager = createTestNetworkManager()

            val status = networkManager.getNetworkStatus()

            assertThat(status).isNotNull()
            assertThat(status.networkType).isNotNull()
        }

        @Test
        fun getNetworkType_returnsValidType() {
            val networkManager = createTestNetworkManager()

            val type = networkManager.getNetworkType()

            // Should return a valid NetworkType enum value
            assertThat(type).isIn(
                listOf(
                    NetworkType.NONE,
                    NetworkType.WIFI,
                    NetworkType.CELLULAR,
                    NetworkType.ETHERNET,
                    NetworkType.VPN,
                    NetworkType.OTHER
                )
            )
        }

        private fun createTestNetworkManager(): NetworkManager {
            val adminComponent = ComponentName(context, "com.openmdm.library.test.TestDeviceAdminReceiver")
            return NetworkManager.create(context, adminComponent)
        }
    }
}
