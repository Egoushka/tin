import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// sherpa-onnx is not on Maven; the release AAR is fetched once and pinned by SHA-256.
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile

fun sha256(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
    check(sha256(sherpaAar) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch: $url" }
}

// A library can't bundle a local AAR, so this module compiles against its classes and the app ships the AAR.
val sherpaClasses = layout.buildDirectory.file("sherpa/classes.jar")
val extractSherpa by tasks.registering(Copy::class) {
    from(zipTree(sherpaAar)) { include("classes.jar") }
    into(layout.buildDirectory.dir("sherpa"))
}

android {
    namespace = "io.github.egoushka.tin.speech"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(extractSherpa) }

dependencies {
    compileOnly(files(sherpaClasses).builtBy(extractSherpa))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
