plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.egoushka.tin"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.egoushka.tin"
        // AppFunctions arrived in Android 16 (API 36).
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    // The Shizuku user service copies libtin.so out of nativeLibraryDir, so it must exist as a file there.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(project(":speech"))
    // A library module cannot carry a local AAR, so the app adds the sherpa-onnx runtime that :speech compiles against.
    implementation(files("../speech/libs/sherpa-onnx-2.0.1.aar"))
}
