plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.openmdm.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openmdm.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default to emulator localhost - override via gradle property: -PmdmServerUrl=https://your-server.com/mdm
        buildConfigField("String", "MDM_SERVER_URL", "\"${findProperty("mdmServerUrl") ?: "http://10.0.2.2:3000/mdm"}\"")
        buildConfigField("String", "DEVICE_SECRET", "\"${findProperty("deviceSecret") ?: "change-me-in-production"}\"")

        // TLS certificate pinning. Empty by default so a freshly-cloned dev
        // build can talk to a local server with a self-signed cert; when the
        // host and a primary pin are both set, ServerCertificatePinner turns
        // pinning on. Release builds MUST supply these — see the
        // assembleRelease/bundleRelease guard at the bottom of this file.
        //
        //   ./gradlew :agent:assembleRelease \
        //       -PmdmServerHost=mdm.example.com \
        //       -PmdmServerPin="sha256/AbCd...=" \
        //       -PmdmServerPinBackup="sha256/EfGh...="
        buildConfigField("String", "MDM_SERVER_HOST", "\"${findProperty("mdmServerHost") ?: ""}\"")
        buildConfigField("String", "MDM_SERVER_PIN", "\"${findProperty("mdmServerPin") ?: ""}\"")
        buildConfigField(
            "String",
            "MDM_SERVER_PIN_BACKUP",
            "\"${findProperty("mdmServerPinBackup") ?: ""}\""
        )
    }

    // Release signing, driven entirely by environment variables so CI can
    // sign with the demo-release keystore (see .github/workflows/release.yml)
    // while a plain local `assembleRelease` keeps producing an unsigned APK,
    // exactly as before. QR provisioning pins the SHA-256 of the signing
    // certificate, so published demo APKs must all carry the same signature —
    // an ad-hoc debug key would change the provisioning checksum every build.
    val releaseKeystorePath: String? = System.getenv("OPENMDM_KEYSTORE_FILE")
    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("OPENMDM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENMDM_KEY_ALIAS")
                keyPassword = System.getenv("OPENMDM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

dependencies {
    // OpenMDM Library
    implementation(project(":library"))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.hilt.navigation.compose)

    // Firebase
    implementation(libs.firebase.messaging)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    // Android Instrumentation Testing
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.coroutines.test)
}

/**
 * Refuse to produce a release artifact without TLS certificate pinning.
 *
 * Pinned-key enrollment is only as strong as the first-enroll connection: an
 * unpinned MITM at enrollment lets an attacker capture the device's public key
 * and own that device's identity permanently. Rather than silently shipping a
 * release with pinning disabled, fail the build.
 *
 * Debug builds are unaffected — they routinely target a local server with a
 * self-signed certificate.
 *
 * Emergency escape hatch (e.g. the pinned certificate expired before a backup
 * pin shipped): -PallowUnpinnedRelease=true
 */
tasks.matching { task ->
    task.name.startsWith("assembleRelease") || task.name.startsWith("bundleRelease")
}.configureEach {
    // Resolve the properties into plain locals at configuration time. A task
    // action that reads `project` (or a script-level property) captures a
    // Gradle script object reference, which the configuration cache cannot
    // serialize — these Strings and Boolean can be.
    val serverHost = providers.gradleProperty("mdmServerHost").orNull.orEmpty()
    val serverPin = providers.gradleProperty("mdmServerPin").orNull.orEmpty()
    val allowUnpinned = providers.gradleProperty("allowUnpinnedRelease").orNull == "true"

    doFirst {
        if (allowUnpinned) {
            logger.warn(
                "⚠️  Building an UNPINNED release (-PallowUnpinnedRelease=true). " +
                    "The agent will trust the system store; first-enroll MITM is possible."
            )
        } else if (serverHost.isBlank() || serverPin.isBlank()) {
            throw GradleException(
                "Release builds require TLS certificate pinning. Pass -PmdmServerHost and " +
                    "-PmdmServerPin (see ServerCertificatePinner for how to compute the pin), " +
                    "or -PallowUnpinnedRelease=true to override for an emergency build."
            )
        }
    }
}
