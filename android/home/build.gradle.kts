plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.egoushka.tin.home"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.egoushka.tin.home"
        // Themed monochrome icons and per-app locales arrived in API 33; RenderEffect in 31.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        // Needs you reads the approvals app's provider; the debug build reads a fake with the same wire shape
        // (src/debug) until the approvals repo ships its side.
        // Homelab reads Gatus on the tailnet; debug reads localhost (`adb reverse tcp:8095 tcp:8095` to a fixture server).
        // The mic runs a scripted engine here so slice 6's checks stay deterministic; `-Ptin.voice=real` uses tin's speech.
        debug {
            buildConfigField("boolean", "REAL_VOICE", (providers.gradleProperty("tin.voice").orNull == "real").toString())
            buildConfigField("String", "NEEDS_YOU_AUTHORITY", "\"io.github.egoushka.tin.home.needsyou.fake\"")
            buildConfigField("String", "GATUS_URL", "\"http://127.0.0.1:8095\"")
        }
        // Debug-signed so CI's release APK installs: Home isn't distributed, and no key material lives in the repo.
        release {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "NEEDS_YOU_AUTHORITY", "\"com.yehor.approvals.needsyou\"")
            buildConfigField("String", "GATUS_URL", "\"http://100.64.0.2:8095\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        localeFilters += listOf("en", "uk")
    }

    lint {
        error += "MissingTranslation"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // The layout check composes Home's frame under Robolectric, which needs the merged resources.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The design tokens are the spec; TokensTest reads them from the repo (docs/design/night-glass).
tasks.withType<Test>().configureEach {
    systemProperty("tin.design", rootProject.file("../docs/design/night-glass").absolutePath)
    // Robolectric's SDK 36 sandbox reaches into java.base on JDK 21.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(project(":voice-ipc"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517") // android.jar's org.json is a stub that returns defaults in unit tests
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
