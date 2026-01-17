# OpenMDM Android

[![JitPack](https://jitpack.io/v/azoila/openmdm-android.svg)](https://jitpack.io/#azoila/openmdm-android)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Official Android components for [OpenMDM](https://github.com/openmdm/openmdm) - the embeddable Mobile Device Management SDK.

> [!CAUTION]
> **This project is under active development and is NOT ready for production use.**
> APIs may change without notice. Use at your own risk.

## Overview

This repository contains:

| Module | Description |
|--------|-------------|
| `:agent` | Full-featured MDM agent app - fork this for customization |
| `:library` | Core MDM library - embed in your own Android app |

## Quick Start

### Option 1: Fork the Agent App

Best for: Building a branded MDM agent with full functionality.

1. Fork this repository
2. Customize branding in `agent/src/main/res/`
3. Update `agent/build.gradle.kts` with your app ID
4. Configure your server URL in the app
5. Build and distribute

```bash
git clone https://github.com/YOUR_ORG/openmdm-android
cd openmdm-android
./gradlew :agent:assembleRelease
```

### Option 2: Use the Library

Best for: Adding MDM capabilities to an existing app.

**Step 1: Add JitPack repository**

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Or in your root `build.gradle.kts`:

```kotlin
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**Step 2: Add the dependency**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.azoila.openmdm-android:library:0.1.0")
}
```

> **Note**: Replace `0.1.0` with the latest release version or use `main-SNAPSHOT` for the latest development version.

## Library Usage

### Device Management

```kotlin
import com.openmdm.library.device.DeviceManager

// Create DeviceManager with your DeviceAdminReceiver
val deviceManager = DeviceManager.create(
    context = applicationContext,
    adminReceiverClass = MyDeviceAdminReceiver::class.java
)

// Check capabilities
if (deviceManager.isDeviceOwner()) {
    // Full MDM capabilities available

    // Silent app installation
    deviceManager.installApkSilently(
        apkUrl = "https://example.com/app.apk",
        packageName = "com.example.app"
    )

    // Grant permissions
    deviceManager.grantCommonPermissions("com.example.app")

    // Kiosk mode
    deviceManager.startLockTaskMode("com.example.app")
}

if (deviceManager.isDeviceAdmin()) {
    // Basic admin capabilities
    deviceManager.lockDevice()
    deviceManager.setCameraDisabled(true)
}
```

### MDM Server Communication

```kotlin
import com.openmdm.library.MDMClient
import com.openmdm.library.api.*

// Create client
val mdmClient = MDMClient.Builder()
    .serverUrl("https://mdm.example.com")
    .deviceSecret("your-shared-secret")
    .debug(BuildConfig.DEBUG)
    .onTokenRefresh { token, refreshToken ->
        // Save tokens for persistence
        prefs.saveTokens(token, refreshToken)
    }
    .onEnrollmentLost {
        // Handle re-enrollment
        navigateToEnrollment()
    }
    .build()

// Enroll device
val timestamp = Instant.now().toString()
val signature = mdmClient.generateEnrollmentSignature(
    model = Build.MODEL,
    manufacturer = Build.MANUFACTURER,
    osVersion = Build.VERSION.RELEASE,
    serialNumber = getSerialNumber(),
    imei = null,
    macAddress = getMacAddress(),
    androidId = getAndroidId(),
    method = "app-only",
    timestamp = timestamp
)

val enrollmentRequest = EnrollmentRequest(
    model = Build.MODEL,
    manufacturer = Build.MANUFACTURER,
    osVersion = Build.VERSION.RELEASE,
    sdkVersion = Build.VERSION.SDK_INT,
    serialNumber = getSerialNumber(),
    imei = null,
    macAddress = getMacAddress(),
    androidId = getAndroidId(),
    agentVersion = BuildConfig.VERSION_NAME,
    agentPackage = packageName,
    method = "app-only",
    timestamp = timestamp,
    signature = signature
)

val result = mdmClient.enroll(enrollmentRequest)
result.onSuccess { response ->
    // Device enrolled successfully
    Log.i("MDM", "Enrolled as device: ${response.deviceId}")
}

// Send heartbeat
val heartbeatRequest = HeartbeatRequest(
    deviceId = mdmClient.getDeviceId()!!,
    timestamp = Instant.now().toString(),
    batteryLevel = getBatteryLevel(),
    isCharging = isCharging(),
    // ... other telemetry
)

val heartbeatResult = mdmClient.heartbeat(heartbeatRequest)
heartbeatResult.onSuccess { response ->
    // Process pending commands
    response.pendingCommands?.forEach { command ->
        processCommand(command)
    }

    // Apply policy updates
    response.policyUpdate?.let { policy ->
        applyPolicy(policy)
    }
}
```

## Device Owner Setup

To enable full MDM capabilities, the agent must be set as Device Owner.

### Using ADB (Development)

```bash
adb shell dpm set-device-owner com.openmdm.agent/.receiver.MDMDeviceAdminReceiver
```

### Zero-Touch Enrollment (Production)

Configure your devices through [Android Zero-Touch](https://www.android.com/enterprise/management/zero-touch/) or Samsung Knox.

### QR Code Provisioning

Generate a provisioning QR code using the OpenMDM CLI:

```bash
npx openmdm enroll qr --output enrollment.png
```

## Project Structure

```
openmdm-android/
├── agent/                    # Full-featured MDM agent app
│   ├── src/main/
│   │   ├── java/.../
│   │   │   ├── receiver/     # DeviceAdminReceiver, BootReceiver
│   │   │   ├── service/      # MDMService, FCM service
│   │   │   ├── ui/           # Compose UI screens
│   │   │   └── util/         # Utilities
│   │   └── res/              # Resources (customize for branding)
│   └── build.gradle.kts
│
├── library/                  # Core MDM library
│   ├── src/main/java/.../
│   │   ├── api/              # API models and Retrofit interface
│   │   ├── device/           # DeviceManager
│   │   └── MDMClient.kt      # High-level client
│   └── build.gradle.kts
│
├── docs/                     # Documentation
├── build.gradle.kts          # Root build file
└── settings.gradle.kts       # Module configuration
```

## Customization Guide

### Branding the Agent

1. **App Icon**: Replace files in `agent/src/main/res/mipmap-*/`
2. **App Name**: Edit `agent/src/main/res/values/strings.xml`
3. **Colors**: Edit `agent/src/main/res/values/colors.xml`
4. **Package Name**: Update `applicationId` in `agent/build.gradle.kts`

### Adding Custom Commands

```kotlin
// In your MDMService or command processor
when (command.type) {
    "custom" -> {
        val customType = command.payload?.get("customType") as? String
        when (customType) {
            "myCustomCommand" -> handleMyCustomCommand(command.payload)
            else -> CommandResult(false, "Unknown custom command")
        }
    }
}
```

### Custom DeviceAdminReceiver

```kotlin
class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Custom initialization
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Cleanup
    }
}
```

## Building

```bash
# Build everything
./gradlew build

# Build agent APK
./gradlew :agent:assembleRelease

# Build library AAR
./gradlew :library:assembleRelease

# Run tests
./gradlew test
```

## Protocol Compatibility

This Android agent is designed to work with [OpenMDM Server](https://github.com/openmdm/openmdm) v0.2.0+.

The API protocol is defined in the `@openmdm/client` TypeScript package. Keep the Kotlin models in sync when updating.

## JitPack Publishing

The library module is published via [JitPack](https://jitpack.io/#azoila/openmdm-android).

### Using a Release Version

Releases are automatically available on JitPack when a GitHub release is created:

```kotlin
implementation("com.github.azoila.openmdm-android:library:0.1.0")
```

### Using a Specific Commit

You can also use any commit hash:

```kotlin
implementation("com.github.azoila.openmdm-android:library:abc1234")
```

### Using the Latest Development Version

For the latest unreleased changes from the main branch:

```kotlin
implementation("com.github.azoila.openmdm-android:library:main-SNAPSHOT")
```

> **Note**: SNAPSHOT versions are cached for 24 hours. Use `--refresh-dependencies` to force update.

### Build Status

Check the JitPack build status at: https://jitpack.io/#azoila/openmdm-android

## Contributing

Contributions welcome! Please read the [Contributing Guide](https://github.com/openmdm/openmdm/blob/main/CONTRIBUTING.md).

## License

MIT License - see [LICENSE](LICENSE) for details.

---

Part of the [OpenMDM](https://openmdm.dev) project.
