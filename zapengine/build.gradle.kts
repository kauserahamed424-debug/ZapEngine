plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bdtv.zapengine"
    // compileSdk 36 is required by mpv-android-lib 0.2.1 (same as the BDTv app).
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    // ExoPlayer (Media3) — fast engine
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")

    // libVLC — broad-coverage engine (exact version used by BDTv dual-engine builds)
    implementation("org.videolan.android:libvlc-all:3.6.2")

    // libmpv — stability engine (exact version used by BDTv v29/v31 builds)
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.2.1")
}
