plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// versionCode is the commit count, so every commit on main is an in-place update; versionName carries it and the
// short SHA rides in BuildConfig.GIT_SHA (a local build with tracked edits gets "-dirty"; CI never does).
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args); workingDir = rootProject.projectDir }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

val commitCount = git("rev-list", "--count", "HEAD")?.toInt() ?: 1
val gitSha = (git("rev-parse", "--short=7", "HEAD") ?: "dev") +
    if (System.getenv("CI") == null && git("status", "--porcelain", "--untracked-files=no") != null) "-dirty" else ""

android {
    namespace = "io.github.egoushka.tin.home"
    // The engine's modules compile against 37.1 and ask the same of the app that uses them.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "io.github.egoushka.tin.home"
        // Themed monochrome icons and per-app locales arrived in API 33; RenderEffect in 31.
        minSdk = 33
        targetSdk = 36
        versionCode = commitCount
        versionName = "0.1.$commitCount"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // The root build creates "release" from tin.signing.* (CI main builds, the Mac's ~/.gradle); without them this is
        // debug-signed so a PR's release APK still installs. No key material lives in the repo.
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    // Kvaesitso's engine (settings.gradle.kts maps these to the vendored projects): apps and icons, and the Koin
    // modules their bindings reach.
    listOf(
        "core-base", "core-permissions", "core-preferences", "core-profiles", "data-applications",
        "data-customattrs", "data-database", "data-i18n", "data-searchable", "services-icons",
    ).forEach { implementation("de.mm20.launcher2:$it") }
    implementation("io.insert-koin:koin-android:4.2.2")
    // The engine's app list is an ImmutableList, which its modules keep to themselves.
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517") // android.jar's org.json is a stub that returns defaults in unit tests
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // On an emulator or the phone: `:home:connectedCheck` (docs/specs/2026-11-04-home-on-kvaesitso.md, the EMU checks).
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
