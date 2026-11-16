plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    // Kotlin 2.3.0 is REQUIRED: mpv-android-lib 0.2.1 was compiled with
    // Kotlin 2.3.0, and older compilers cannot read its metadata
    // (build fails with a misleading internal compiler error).
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}
