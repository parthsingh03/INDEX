// Top-level build file for INDEX.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
    // Firebase (cloud mode) — versions per FIREBASE_SETUP.md step 3.
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.firebase-bom") version "33.3.0" apply false
}
