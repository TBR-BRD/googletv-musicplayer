plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.tbrbd.onradiotv"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.tbrbd.onradiotv"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            // The TV hardware tested against (and the emulator used earlier
            // this session) covers both 32-bit (armeabi-v7a) and 64-bit
            // (arm64-v8a) ARM - no need for x86/x86_64.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
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
    }
    // No composeOptions.kotlinCompilerExtensionVersion here: with Kotlin 2.0+
    // the Compose compiler is a separate Gradle plugin (applied above) that
    // auto-matches the Kotlin version, rather than a manually pinned version
    // number that would need to track Kotlin releases by hand.
}

dependencies {
    // Plain stable Compose Material3 (not the alpha androidx.tv libraries) -
    // D-pad navigation is wired manually via FocusManager.moveFocus() and
    // onKeyEvent, see ui/TvScreen.kt. This trades a purpose-built TV widget
    // set for a much more stable, well-documented API surface.
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")

    // Google Cast support is hand-rolled (CastV2Client/CastDiscoveryManager
    // in the cast/ package) using plain Android NsdManager + a TLS socket,
    // not the official play-services-cast-framework SDK - that SDK's
    // CastContext needs a Play Services module not available on at least
    // one real (non-Google-TV-certified) device tested. No extra
    // dependency needed for it: NsdManager and javax.net.ssl are both
    // built into the platform.

    // Networking + image loading
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
