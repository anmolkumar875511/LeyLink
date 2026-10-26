pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LeyLink"

// Lets us write `projects.core.routing` instead of project(":core:routing")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:routing")
include(":core:testing")
include(":data:storage")
include(":transport:ble")
include(":transport:wifidirect")
