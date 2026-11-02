plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}

// One release key for tin and Home: VoiceService's permission is signature-level, so both APKs must share a signer.
// The key lives outside the repo; set these in ~/.gradle/gradle.properties (never commit them):
//   tin.signing.storeFile, tin.signing.storePassword, tin.signing.keyAlias, tin.signing.keyPassword
// Without tin.signing.storeFile, release builds stay unsigned.
val signingStore = providers.gradleProperty("tin.signing.storeFile").orNull
subprojects {
    plugins.withId("com.android.application") {
        if (signingStore == null) return@withId
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            val release = signingConfigs.create("release") {
                storeFile = rootProject.file(signingStore)
                storePassword = providers.gradleProperty("tin.signing.storePassword").get()
                keyAlias = providers.gradleProperty("tin.signing.keyAlias").get()
                keyPassword = providers.gradleProperty("tin.signing.keyPassword").get()
            }
            buildTypes.matching { it.name == "release" }.configureEach { signingConfig = release }
        }
    }
}
