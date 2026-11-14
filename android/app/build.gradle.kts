plugins {
    id("com.android.application")
}

// Same scheme as Home: versionCode is the commit count, so each release is an in-place update for Obtainium.
val commitCount = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); workingDir = rootProject.projectDir }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "io.github.egoushka.tin"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.egoushka.tin"
        // AppFunctions arrived in Android 16 (API 36).
        minSdk = 36
        targetSdk = 36
        versionCode = commitCount
        versionName = "0.1.$commitCount"
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

    buildTypes {
        release {
            // No R8: the Shizuku user service, the AIDL stubs and the JNI entry points are found by name.
            isMinifyEnabled = false
            // scripts/speech-push-model.sh copies the speech model into the app's folder with `run-as`, which only
            // works on a debuggable app. The signature-level VOICE permission, not debuggability, guards the mic.
            isDebuggable = true
        }
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
    implementation(project(":voice-ipc"))
    // A library module cannot carry a local AAR, so the app adds the sherpa-onnx runtime that :speech compiles against.
    implementation(files("../speech/libs/sherpa-onnx-2.0.1.aar"))
}
