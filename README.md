# OpenMDM Android

[![JitPack](https://jitpack.io/v/azoila/openmdm-android.svg)](https://jitpack.io/#azoila/openmdm-android)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Official Android components for [OpenMDM](https://github.com/azoila/openmdm) - the embeddable Mobile Device Management SDK.

> [!NOTE]
> **Under active development.** APIs may change between 0.x releases. The
> agent is ready for evaluation and pilots — see the
> [prebuilt demo APK](#option-0-try-the-prebuilt-demo-agent) — but a full
> real-hardware Device Owner verification pass is still in progress, so we
> don't yet recommend it for production fleets.

## Overview

This repository contains:

| Module | Description |
|--------|-------------|
| `:agent` | Full-featured MDM agent app - fork this for customization |
| `:library` | Core MDM library - embed in your own Android app |

## Quick Start

### Option 0: Try the Prebuilt Demo Agent

Best for: evaluating OpenMDM without building anything.

Every [GitHub release](https://github.com/azoila/openmdm-android/releases)
ships a signed, generic demo agent APK (`openmdm-agent-<tag>.apk`) plus a
`provisioning-checksum.txt` containing the ready-to-use
`PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` for QR provisioning. The APK
is server-agnostic — the server URL and enrollment configuration arrive via
the provisioning QR's admin-extras bundle, so one APK works against any
OpenMDM server.

The demo APK is built without a TLS certificate pin (fine for evaluation;
production fleets should build their own pinned agent — see
[Building](#building)).

For a guided end-to-end walkthrough against a local server, follow the
[openmdm-demo Android Quick Start](https://github.com/azoila/openmdm-demo/blob/main/docs/android-quickstart.md).

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
    implementation("com.github.azoila.openmdm-android:library:0.3.0")
}
```

> **Note**: Replace `0.3.0` with the latest release version or use `main-SNAPSHOT` for the latest development version.

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

> **How enrollment authenticates.** The agent app prefers *device-pinned-key*
> enrollment: it requests a single-use challenge from
> `GET /agent/enroll/challenge`, generates an ECDSA P-256 keypair in the
> device's hardware Keystore, and signs the canonical enrollment message; the
> server verifies and pins the public key (requires OpenMDM server ≥ 0.9 with
> challenge storage). The HMAC path shown below — signing with a shared
> `deviceSecret` — is the fallback, and remains the primary path for library
> embedders using `MDMClient` directly. All client requests carry the
> `X-Openmdm-Protocol: 2` header, which requires server ≥ 0.3.0.

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

The device must have no accounts and no secondary users. Note that the ADB
path grants Device Owner but delivers **no provisioning extras** — the agent
has no server URL from this flow, so it uses the compiled-in
`MDM_SERVER_URL` (gradle `-PmdmServerUrl`, default `http://10.0.2.2:3000/mdm`
for emulators) and you enroll manually from the agent's enrollment screen.

### QR Code Provisioning

A factory-reset device (tap the welcome screen 6 times to launch the
scanner) provisions from a QR containing the standard Android DPC extras
plus OpenMDM configuration in the admin-extras bundle:

| Admin-extras key | Meaning |
|---|---|
| `openmdm.server_url` | MDM base URL, as reachable from the device (required for self-enrollment) |
| `openmdm.device_secret` | enrollment secret for the HMAC path (optional with pinned-key enrollment) |
| `openmdm.enrollment_token` | enrollment token / device code (optional) |
| `openmdm.policy_id`, `openmdm.group_id` | initial assignment (optional) |

Generate the QR with the OpenMDM CLI (≥ 0.6.0):

```bash
npx @openmdm/cli enroll qr \
  --server-url https://mdm.example.com/mdm \
  --apk-url https://github.com/azoila/openmdm-android/releases/download/<tag>/openmdm-agent-<tag>.apk \
  --checksum <from the release's provisioning-checksum.txt> \
  --output enrollment.png
```

After scanning, the platform downloads and verifies the APK, sets it as
Device Owner, and the agent self-enrolls on first connectivity
(`adb logcat -s MDMDeviceAdmin EnrollmentWorker` to follow along).

If you built your own APK, compute its checksum with `apksigner` — the APK
is signed with APK Signature Scheme v2+, which `keytool -printcert -jarfile`
cannot read:

```bash
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
"$BT/apksigner" verify --print-certs agent-release.apk \
  | awk '/certificate SHA-256 digest/ {print $NF; exit}' \
  | xxd -r -p | base64 | tr '+/' '-_' | tr -d '='
```

### Zero-Touch Enrollment (Production)

Configure your devices through [Android Zero-Touch](https://www.android.com/enterprise/management/zero-touch/) or Samsung Knox, using the same DPC component and admin-extras bundle as the QR payload.

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

This Android agent speaks protocol v2 (the `X-Openmdm-Protocol: 2` header)
and requires [OpenMDM Server](https://github.com/azoila/openmdm) **≥ 0.3.0**.
Device-pinned-key enrollment additionally requires server **≥ 0.9** with a
database adapter that implements challenge storage (the bundled Drizzle
adapter does); against older servers the agent falls back to HMAC enrollment
automatically. The latest server release is recommended.

The API protocol is defined in the `@openmdm/client` TypeScript package. Keep the Kotlin models in sync when updating.

## JitPack Publishing

The library module is published via [JitPack](https://jitpack.io/#azoila/openmdm-android).

### Using a Release Version

Releases are automatically available on JitPack when a GitHub release is
created (each release also ships the signed demo agent APK as an asset —
see [Quick Start, Option 0](#option-0-try-the-prebuilt-demo-agent)):

```kotlin
implementation("com.github.azoila.openmdm-android:library:0.3.0")
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

Contributions welcome! Please read the [Contributing Guide](CONTRIBUTING.md).

## License

MIT License - see [LICENSE](LICENSE) for details.

---

Part of the [OpenMDM](https://openmdm.dev) project.
