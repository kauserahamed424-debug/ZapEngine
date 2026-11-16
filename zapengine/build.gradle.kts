plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // ExoPlayer (Media3) — fast engine
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    // OkHttp stack — used ONLY when DNS-over-HTTPS is enabled (ExoEngine)
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")

    // libVLC — broad-coverage engine (exact version used by BDTv dual-engine builds)
    implementation("org.videolan.android:libvlc-all:3.6.2")

    // libmpv — stability engine (exact version used by BDTv v29/v31 builds)
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.2.1")
}

// JitPack publishing — consumers add the library with one line:
//   implementation("com.github.kauserahamed424-debug:ZapEngine:1.0.6")
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.kauserahamed424-debug"
            artifactId = "ZapEngine"
            version = "1.0.6"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
