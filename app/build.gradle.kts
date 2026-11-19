plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.underrated.docreader"

    // The only SDK platform installed on this machine is android-37.0 (API 37,
    // Android 17), and there is no sdkmanager here to fetch another one.
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.underrated.docreader"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Per-ABI APKs so the native payload of each architecture can be measured
    // exactly, plus a universal APK for side-by-side comparison.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Spike: second rendering engine behind the same PdfSource interface.
    // Maintained Kotlin fork of PdfiumAndroid; ships all four ABIs.
    // 1.0.32 contains 16 KB-aligned 64-bit native libraries; keep the same
    // PDFium API line while supporting Android devices with 16 KB pages.
    implementation("io.legere:pdfiumandroid:1.0.32")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
}
