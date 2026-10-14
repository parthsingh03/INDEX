plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// NOTE for the next person: the google-services plugin is intentionally NOT
// applied yet. INDEX runs fully in local mode (Room database on the phone)
// until the Firebase project exists and google-services.json is added.
// See FIREBASE_SETUP.md at the project root for the exact wiring steps.

// --- Release signing ---------------------------------------------------------
// INDEX is signed with ONE fixed release key for its whole life, so every
// future APK installs over the old one as an update and nobody loses data.
// The key itself is never committed. Its location and passwords come from
// gradle properties or environment variables:
//   INDEX_STORE_FILE, INDEX_STORE_PASSWORD, INDEX_KEY_ALIAS, INDEX_KEY_PASSWORD
// (gradle property names: indexStoreFile, indexStorePassword, indexKeyAlias,
//  indexKeyPassword). If they are absent (e.g. CI debug builds), the release
// build simply stays unsigned instead of failing.
val indexStoreFile = (findProperty("indexStoreFile") as String?) ?: System.getenv("INDEX_STORE_FILE")
val indexStorePassword = (findProperty("indexStorePassword") as String?) ?: System.getenv("INDEX_STORE_PASSWORD")
val indexKeyAlias = (findProperty("indexKeyAlias") as String?) ?: System.getenv("INDEX_KEY_ALIAS")
val indexKeyPassword = (findProperty("indexKeyPassword") as String?) ?: System.getenv("INDEX_KEY_PASSWORD")

android {
    namespace = "com.parth.index"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.parth.index"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("indexRelease") {
            if (indexStoreFile != null) {
                storeFile = file(indexStoreFile)
                storePassword = indexStorePassword
                keyAlias = indexKeyAlias
                keyPassword = indexKeyPassword
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
            if (indexStoreFile != null) {
                signingConfig = signingConfigs.getByName("indexRelease")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Room — the on-phone database used in local mode.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Coroutines — all heavy work (file copies, PDF building) runs off the
    // main thread so the app never hangs.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
