// app/build.gradle.kts — Synctuary Android client (single-module phase)
//
// The Compose plugin (Kotlin 2.0+) handles compiler config that used to live
// under composeOptions{}; we keep buildFeatures.compose=true to enable the
// Compose feature flag in AGP itself.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace  = "io.synctuary.android"
    compileSdk = 34

    defaultConfig {
        applicationId             = "io.synctuary.android"
        minSdk                    = 26          // BiometricPrompt needs API 28; 26 floor for tonal palette.
        targetSdk                 = 34
        versionCode               = 16
        versionName               = "0.7.18"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false             // Will turn on once the API surface settles; current proguard rules are TBD.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable        = true
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            output.outputFileName = "synctuary-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Enable explicit-API mode once the public surface stabilizes.
        // freeCompilerArgs += listOf("-Xexplicit-api=strict")
    }

    buildFeatures {
        compose      = true
        buildConfig  = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Required for Room schema export — keeps schemas under version control
    // for review of structural diffs alongside the migration files.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental",    "true")
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.appcompat)

    // Compose: the BOM pins compose-* artifact versions to a tested matrix.
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.core)
    debugImplementation(libs.bundles.compose.debug)

    // Network
    implementation(libs.bundles.network)
    ksp(libs.moshi.kotlin.codegen)

    // Media preview: Coil for images, ExoPlayer for video/audio
    implementation(libs.coil.compose)
    implementation(libs.bundles.media3)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Room (favorites cache, paired-server registry)
    implementation(libs.bundles.room.runtime)
    ksp(libs.room.compiler)

    // DataStore (small key-value: server URL, transport profile, last-seen times)
    implementation(libs.datastore.preferences)

    // DocumentFile: SAF tree-URI traversal for the local file browser
    implementation(libs.androidx.documentfile)

    // WorkManager: periodic photo auto-backup
    implementation(libs.androidx.work.runtime)

    // Camera + barcode scanning (QR code pairing)
    implementation(libs.bundles.camerax)
    implementation(libs.mlkit.barcode)

    // Security: biometric prompt for the §8.9 hidden-list gate;
    // EncryptedSharedPreferences for the device_token; Bouncy Castle
    // for Ed25519 + HKDF (PROTOCOL §3 / §4.1).
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.bouncycastle.bcprov)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
