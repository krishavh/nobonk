plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ── Release signing credentials (T-REL-SDK) ───────────────────────────────────
// Secrets are NEVER committed. Provide them at build time via either:
//   1. ~/.gradle/gradle.properties  (recommended — outside the repo), or
//   2. environment variables (recommended for CI).
// Expected keys/vars:
//   NOBONK_STORE_FILE      absolute path to the upload keystore (.jks)
//   NOBONK_STORE_PASSWORD  keystore password
//   NOBONK_KEY_ALIAS       key alias
//   NOBONK_KEY_PASSWORD    key password
// See docs/RELEASE_CHECKLIST.md for keystore generation + how to supply these.
fun releaseCredential(name: String): String? =
    (project.findProperty(name) as String?) ?: System.getenv(name)

val hasReleaseSigning: Boolean = releaseCredential("NOBONK_STORE_FILE") != null

android {
    namespace = "com.persondetection.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.persondetection.android"
        minSdk = 29        // Android 10+ (floor 26 for the SYSTEM_ALERT_WINDOW overlay; 29 ≈ 95%+ device reach)
        targetSdk = 36     // Android 16 — required for new-app submissions (Play API-36 cutoff)
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Populated only when credentials are supplied (see releaseCredential above).
        // Absent them, `bundleRelease`/`assembleRelease` produce an UNSIGNED artifact
        // and `assembleDebug` is unaffected — so the project still builds on any machine.
        create("release") {
            val storeFilePath = releaseCredential("NOBONK_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = releaseCredential("NOBONK_STORE_PASSWORD")
                keyAlias = releaseCredential("NOBONK_KEY_ALIAS")
                keyPassword = releaseCredential("NOBONK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // Enable R8 obfuscation + shrinking (SEC-01)
            isShrinkResources = true    // Remove unused resources from the APK
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false     // Keep debug builds fast and readable
        }
    }

    compileOptions {
        // Core library desugaring lets us use java.time / newer java.util APIs on API 29.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        // Enables the generated BuildConfig, whose DEBUG flag gates every Dbg.* log call
        // so release artifacts emit no app logs (SEC-N04 / T-SEC-LOGGING).
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Keep native libs uncompressed and page-aligned in the APK/AAB.
            // Required for the Play 16 KB-page requirement (Nov 2025+ submissions).
            // Do NOT set extractNativeLibs=true / useLegacyPackaging=true.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ONNX Runtime for ML inference
    implementation(libs.onnxruntime.android)

    // Encrypted on-device history at rest (Keystore master key + EncryptedFile)
    implementation(libs.androidx.security.crypto)

    // Core library desugaring runtime (enables java.time etc. down to minSdk)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Unit tests (pure-Kotlin safety-core tests under src/test)
    testImplementation(libs.junit)
}
