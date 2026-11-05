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

// Kvaesitso's engine (GPL-3.0), vendored untouched by git subtree; its own build, so its catalog and plugins stay
// upstream's. Home names each engine module it uses here; the edits beyond upstream are in kvaesitso.patches.
includeBuild("kvaesitso") {
    dependencySubstitution {
        listOf(
            ":core:base",
            ":core:permissions",
            ":core:preferences",
            ":core:profiles",
            ":data:applications",
            ":data:customattrs",
            ":data:database",
            ":data:i18n",
            ":data:searchable",
            ":services:icons",
        ).forEach { substitute(module("de.mm20.launcher2:" + it.drop(1).replace(':', '-'))).using(project(it)) }
    }
}

include(":app")
include(":home")
include(":speech")
include(":fixture")
include(":voice-ipc")
