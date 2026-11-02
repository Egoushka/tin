plugins {
    id("com.android.library")
}

// Only AIDL: tin serves IVoice and Home binds it, so both compile against the same descriptors.
android {
    namespace = "io.github.egoushka.tin.voice"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
