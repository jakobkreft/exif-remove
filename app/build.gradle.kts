plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing material never lives in the repo. Supply it through
// ~/.gradle/gradle.properties or the environment; without it the release
// build simply comes out unsigned, which is exactly what F-Droid's build
// server produces when it rebuilds this tag to compare against the
// published APK.
val releaseStoreFile: String? =
    providers.gradleProperty("RELEASE_STORE_FILE").orNull
        ?: System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword: String? =
    providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
        ?: System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? =
    providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
        ?: System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? =
    providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
        ?: System.getenv("RELEASE_KEY_PASSWORD")

android {
    namespace = "si.jakobkreft.exifremove"
    compileSdk = 37

    defaultConfig {
        applicationId = "si.jakobkreft.exifremove"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 is not needed at minSdk 26 and its per-entry signing
                // would otherwise perturb the archive F-Droid compares.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            // AGP otherwise bakes the git HEAD into META-INF as
            // version-control-info.textproto. That makes the APK differ
            // between two checkouts of the same source, which defeats
            // reproducible builds — and ships build provenance in an app
            // whose whole job is removing metadata.
            vcsInfo { include = false }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // ExifInterface is pure-Java parsing; stub android.util.Log etc.
        // so the engine can be exercised in JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }
    dependenciesInfo {
        // Reproducible builds for F-Droid: no Google-signed dependency metadata
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
