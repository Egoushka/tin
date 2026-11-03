pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "tin"
include(":app")
include(":home")
include(":speech")
include(":fixture")
include(":voice-ipc")
