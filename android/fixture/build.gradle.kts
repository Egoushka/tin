plugins {
    id("com.android.application")
}

// A throwaway app for the device checks of docs/specs/2026-11-03-home-apps.md: install, uninstall, hide and notify
// without touching an app Yehor uses. It is never released.
android {
    namespace = "io.github.egoushka.tin.fixture"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.egoushka.tin.fixture"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
