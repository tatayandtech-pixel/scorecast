plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

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

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Phase 1: no shrinking yet. Sideload signing comes in Phase 6.
            isMinifyEnabled = false
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
