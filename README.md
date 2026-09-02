# OpenMDM Android

[![JitPack](https://jitpack.io/v/azoila/openmdm-android.svg)](https://jitpack.io/#azoila/openmdm-android)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Official Android components for [OpenMDM](https://github.com/azoila/openmdm) - the embeddable Mobile Device Management SDK.

> [!NOTE]
> **Under active development.** APIs may change between 0.x releases. The
> agent is ready for evaluation and pilots — see the
> [prebuilt demo APK](#option-0-try-the-prebuilt-demo-agent) — but a full
> real-hardware Device Owner verification pass is still in progress, so we
> don't yet recommend it for production fleets — see
> [Verified hardware](#verified-hardware) for what has actually been run.
>
> **QR, NFC and zero-touch provisioning additionally depend on Google's
> [DPC allowlist](#play-protect-and-the-dpc-allowlist), which OpenMDM is not on
> — QR provisioning is confirmed blocked on GMS hardware.** The
> [ADB path](#using-adb-development) does not depend on it and is the way to
> evaluate today.

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

Provisioning this APK by QR depends on Google's DPC allowlist, which OpenMDM is
not on, and is confirmed blocked on GMS hardware — read
[Play Protect and the DPC allowlist](#play-protect-and-the-dpc-allowlist) before
you factory-reset a device for it.

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

> [!IMPORTANT]
> Play Protect identifies a DPC by its **signing certificate**, so a fork signed
> with your own key is a distinct app to it — even with the same package name and
> the same source commit. It will be blocked during QR, NFC and zero-touch
> provisioning until Android Enterprise approves it, and getting that approval is
> a Google process you should budget for before committing to a fork. See
> [Play Protect and the DPC allowlist](#play-protect-and-the-dpc-allowlist).
> Development builds and ADB provisioning are unaffected.

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

### Play Protect and the DPC allowlist

> [!IMPORTANT]
> Since late 2025, Google Play Protect enforces an **allowlist of approved device
> policy controllers**. Only a DPC that Android Enterprise has verified and
> approved may be installed during enterprise enrollment provisioning; a
> non-approved one is blocked with *"App blocked to protect your device"* and
> **provisioning cannot continue**. See
> [Approved Android Enterprise device policy controllers allowlist](https://support.google.com/work/android/answer/16694822).

Which paths this gates:

| Provisioning path | Gated? | Why |
|---|---|---|
| QR, NFC, `afw#`, zero-touch | **Yes** | The platform downloads and installs the DPC for you, and Play Protect verifies it first. |
| `adb shell dpm set-device-owner` | **No** | You install the APK yourself; nothing goes through provisioning-time verification. |

**The OpenMDM demo agent is not on the allowlist**, and has not been submitted
for approval. An independent evaluation established this with a control: on a
Samsung SM-X210 / Android 16, Google's own TestDPC 9.0.12 — served from the same
host, over the same network, on the same factory-reset device — passed Play
Protect and completed provisioning, while the byte-identical upstream v0.4.0 APK
was blocked with the non-approved-DPC message. That isolates the block to the DPC
identity: not the network, not the host, not the device, not the QR payload.

**A successful QR run is not evidence of approval.** The same APK and certificate
were accepted on one attempt and blocked on a later one. Play Protect's
antimalware verdict — the `SAFE` you may see in the logs — is a *different check*
from the Android Enterprise DPC allowlist, and passing the first says nothing
about the second.

**Your own build is a different app.** Play Protect identifies a DPC by its
signing certificate, so rebuilding from this repository with your own key is not
covered by any approval the published APK may have. Same package name, same
source commit, one cosmetic label change — still a new app to Play Protect, and
expected to be blocked.

**Getting a DPC approved** is a Google process, not an OpenMDM one, and there is
no supported way around it:

1. Verify the app complies with
   [Mobile Unwanted Software](https://developers.google.com/android/play-protect/mobile-unwanted-software)
   and is not a
   [Potentially Harmful Application](https://developers.google.com/android/play-protect/potentially-harmful-applications).
   Google calls out device financing solutions, standalone monitoring or
   eavesdropping tools, and pushing or preloading apps without explicit user
   consent as disqualifying.
2. File a
   [Play Protect appeal](https://support.google.com/googleplay/android-developer/contact/protectappeals)
   for the blocked DPC.
3. If you ship through an EMM, confirm with Android Enterprise that your DPC is
   on the allowlist.

Google publishes no review timeline, and community reports describe multi-week
round trips with repeat submissions. Plan for it before committing a fleet to a
custom DPC.

#### Testing while unapproved

There is no documented developer exemption, test account, or staging channel for
an unapproved DPC — neither Google's allowlist page nor community write-ups
describe one. What remains:

| What you want to exercise | How |
|---|---|
| Device Owner APIs — policy, kiosk, commands, lock task | [ADB](#using-adb-development) on GMS hardware. Note it delivers **no** provisioning extras, so it does *not* cover the QR → admin-extras → enrollment path. |
| The full QR path, admin extras included | A **non-GMS / AOSP** device or emulator image. With no Google Play there is no Play Protect DPC check, and custom-DPC QR provisioning works as designed. |
| The full QR path on GMS hardware | Not available until the DPC is approved. |

Do not try to work around the block. It is doing its job, and an approach that
defeats it is grounds for rejection when you do apply.

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

> [!NOTE]
> This path goes through Play Protect's DPC verification. Read
> [Play Protect and the DPC allowlist](#play-protect-and-the-dpc-allowlist)
> first — it decides whether any of the below can work on your device. Check
> the [version matrix](#version-matrix) too: a version-incorrect server fails
> this flow *after* Device Owner is set, which reads like a provisioning bug
> and is not one.

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
Device Owner, and the agent self-enrolls on first connectivity:

```bash
adb logcat -s ProvisioningMode PolicyCompliance ProvisioningHandoff MDMDeviceAdmin EnrollmentWorker
```

`PolicyCompliance` reports `server=<url>` or `server=not supplied`, which is the
quickest way to tell "the QR never carried a server URL" apart from "it did, and
enrollment failed later".

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

Like QR, this path installs the DPC through the platform, so it requires an
approved DPC — see
[Play Protect and the DPC allowlist](#play-protect-and-the-dpc-allowlist).

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

## Compatibility

### Version matrix

This Android agent speaks protocol **v2** (the `X-Openmdm-Protocol: 2` header).
These are the pieces that have to agree, for agent **v0.4.0**:

| Component | Minimum | Latest published | Why the minimum |
|---|---|---|---|
| [OpenMDM Server](https://github.com/azoila/openmdm) (`@openmdm/core`) | 0.3.0 | 0.11.1 | Protocol v2 floor. |
| ↳ for device-pinned-key enrollment | 0.9.0 | 0.11.1 | `GET /agent/enroll/challenge` must exist, and the database adapter must implement challenge storage — the bundled Drizzle adapter does. Older servers answer 503 and the agent falls back to HMAC on its own. |
| [`@openmdm/cli`](https://www.npmjs.com/package/@openmdm/cli) | 0.6.0 | 0.6.2 | The `enroll qr` subcommand. |
| [`openmdm-demo`](https://github.com/azoila/openmdm-demo) | — | unversioned | Ships no releases or tags; track `main`, which pins its own server package versions. |
| Android | 8.0 (API 26) | — | `minSdk 26`; the agent targets API 35. QR and zero-touch provisioning need the API 31+ handshake, which the agent implements. |

Latest-published figures are as of 2026-09-02; running the newest release of
each is recommended. The API protocol itself is defined in the `@openmdm/client`
TypeScript package — keep the Kotlin models in sync when updating it.

Two things the matrix cannot express, both of which will stop an enrollment
that is otherwise version-correct:

- **The QR must carry a server URL.** Without `openmdm.server_url` in the
  admin-extras bundle the device becomes Device Owner and has nothing to enroll
  against. See [QR Code Provisioning](#qr-code-provisioning).
- **Without an `openmdm.enrollment_token`, the server must be configured to
  auto-enroll** (`autoEnroll: true`, the demo default). The agent sends an empty
  token, and a server that requires one will reject it.

### Verified hardware

Device Owner behaviour varies by OEM and Android version, so this table records
what has actually been *run*, not what ought to work.

| Device | Android | DPC | QR → Device Owner | Server enrollment | Policy applied | Source |
|---|---|---|---|---|---|---|
| Samsung SM-X210 | 16 | OpenMDM v0.4.0 | ❌ blocked by Play Protect (accepted once, blocked on later attempts) | ❌ | ❌ | community report |
| Samsung SM-X210 | 16 | *control:* Google TestDPC 9.0.12 | ✅ | n/a | n/a | same reporter, same host and network |

The TestDPC row is a control, not an OpenMDM result. It is what makes the row
above it readable: the hardware, the setup wizard, the QR flow, the hosting and
the network are all fine, and what fails is the DPC identity — see
[Play Protect and the DPC allowlist](#play-protect-and-the-dpc-allowlist).

No maintainer-run end-to-end verification on physical hardware is recorded yet —
this is exactly the gap the development-status note at the top of this README
refers to. The community report above also predates the fix in which enrollment
is queued from the policy-compliance activity rather than from the
`PROFILE_PROVISIONING_COMPLETE` broadcast alone; note that its enrollment column
was never reached, because provisioning was blocked before the agent ran.

If you complete a run — or fail one — please open an issue with the device model,
Android version, agent version, and the logcat output from the tags listed under
[QR Code Provisioning](#qr-code-provisioning). Failed runs are as useful as
successful ones here, and both get added to this table.

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
