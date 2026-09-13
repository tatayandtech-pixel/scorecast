import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Release signing credentials live in a gitignored keystore.properties at the repo root — see
// keystore.properties.template. Absent (fresh clone, CI), the file simply doesn't exist and
// release builds fall back to unsigned rather than failing the whole configuration phase.
// Never inline the passwords here: this file is committed to a public repo.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.scorecast.app"
    compileSdk = 36            // AndroidX core 1.17.0 (via StreamPack) requires API 36 to compile against.

    defaultConfig {
        applicationId = "com.scorecast.app"
        minSdk = 26
        targetSdk = 34            // Android 14 foreground-service-type rules apply (spec §10).
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
            // v2/v3 sign the APK as a whole rather than per-entry like v1, so a repackaged APK
            // can't be passed off as this one. This is what makes the OS reject a tampered update.
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Signed with the owner's release key so Android's own signature-match check rejects a
            // repackaged "update". Null (unsigned) when keystore.properties is absent.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // StreamPack 3.1.2 — capture + composite + H.264 encode + RTMP egress.
    implementation(libs.streampack.core)
    implementation(libs.streampack.rtmp) // RtmpEndpoint is loaded reflectively by DynamicEndpoint.

    // QR pairing (spec §3/§9) — generate on main, scan on mirror.
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)

    // Firebase (spec §3/§4) — Realtime Database + Anonymous Auth for two-device sync (Phase 5).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
    implementation(libs.firebase.auth)

    // Facebook Login SDK (Phase 8) — Page access tokens for Graph API live video creation.
    implementation(libs.facebook.login)
}
