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
        debug { buildConfigField("String", "NEEDS_YOU_AUTHORITY", "\"io.github.egoushka.tin.home.needsyou.fake\"") }
        release { buildConfigField("String", "NEEDS_YOU_AUTHORITY", "\"com.yehor.approvals.needsyou\"") }
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
        unitTests.isReturnDefaultValues = true
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
