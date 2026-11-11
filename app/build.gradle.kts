import java.security.MessageDigest

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
    namespace = "ai.genwhy.nobonk"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.genwhy.nobonk"
        minSdk = 29        // Android 10+ (floor 26 for the SYSTEM_ALERT_WINDOW overlay; 29 ≈ 95%+ device reach)
        targetSdk = 36     // Android 16 — required for new-app submissions (Play API-36 cutoff)
        versionCode = 27
        versionName = "1.0.24"
        // Walking reminders need physical-device acceptance before public release.
        buildConfigField("boolean", "WALKING_REMINDERS_AVAILABLE", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    // Sideload APKs per ABI (Play uses the AAB, which slices automatically).
    splits {
        abi {
            isEnable = !project.hasProperty("bundle")  // -Pbundle for bundleRelease (AGP issue 402800800)
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
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
            buildConfigField("boolean", "WALKING_REMINDERS_AVAILABLE", "true")
        }
        // Private device acceptance without replacing a Google Play-signed install.
        // Uses release optimization and main sources, not debug test harnesses.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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

// Model binaries are distributed separately from Git. Fail the build rather than
// create an installable app whose scanner cannot load either shipped model.
val verifiedModels = mapOf(
    "yolo26n_416.onnx" to "9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d",
    "yolo26s_416.onnx" to "706b34041df74890c4d5720b26e47ced3d825a3b7dd1bad6cf73fcdcb7740356"
)
val modelAssets = layout.projectDirectory.dir("src/main/assets")
val verifyBundledModels by tasks.registering {
    inputs.files(verifiedModels.keys.map { modelAssets.file(it) })
    doLast {
        verifiedModels.forEach { (name, expected) ->
            val model = modelAssets.file(name).asFile
            check(model.isFile) { "Missing $name. Restore the verified release model before building." }
            val digest = MessageDigest.getInstance("SHA-256")
            model.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Unexpected model checksum for $name. Review the model change before building." }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyBundledModels) }

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
    implementation(libs.androidx.fragment)   // app-update-ktx transitively pulls an old fragment; ActivityResult APIs need >= 1.3
    implementation(libs.play.app.update.ktx)   // Google Play flexible in-app updates (Play-installed builds only)

    // Core library desugaring runtime (enables java.time etc. down to minSdk)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Unit tests (pure-Kotlin safety-core tests under src/test)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Android 16 removed InputManager.getInstance; older transitive Espresso crashes before assertions.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
