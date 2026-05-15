import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.bluewave_mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.bluewave_mobile"
        // Android 12+ — runtime BLUETOOTH_CONNECT/SCAN landed in API 31, and
        // the legacy ACCESS_FINE_LOCATION-for-discovery dance is gone above
        // that level. Keeping minSdk = 31 lets the permission gate stay on
        // the modern split-permission code path only.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // libsignal-android targets bytecode that includes
        // java.time / java.util.function APIs not present in
        // Android < 26. Core library desugaring lets the
        // existing minSdk = 31 build pull those classes from a
        // backport JAR rather than requiring an even higher
        // minSdk.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite.bundled)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Signal Protocol — used by `SignalEngine` to drive X3DH + Double
    // Ratchet between two BlueWave peers. The Android variant ships
    // the JNI .so files for arm64 / armv7 / x86_64 so on-device
    // crypto runs natively; the pure-JVM client is wired into the
    // unit-test classpath so the same protocol round-trips on the
    // host JVM as well.
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // DataStore — backs the local profile card (name / @tag / bio /
    // avatar) and the per-user preferences screen (theme, language,
    // visibility timer). Preferences flavour, not Proto.
    implementation(libs.androidx.datastore.preferences)

    // AppCompat (only for AppCompatDelegate.setApplicationLocales —
    // we keep ComponentActivity as the Activity base class).
    implementation(libs.androidx.appcompat)

    // QR rendering for the profile-share screen.
    implementation(libs.zxing.core)

    // Coil for Compose — used by the profile and chat headers to
    // decode avatar URIs without blocking the UI thread.
    implementation(libs.coil.compose)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.libsignal.client)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
