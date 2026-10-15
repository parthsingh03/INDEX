// Top-level build file for INDEX.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
    // Firebase (cloud mode) — versions per FIREBASE_SETUP.md step 3.
    id("com.google.gms.google-services") version "4.4.2" apply false
    // NOTE: firebase-bom is NOT a plugin — it is used as a platform()
    // dependency in app/build.gradle.kts. Declaring it here breaks the build.
}
