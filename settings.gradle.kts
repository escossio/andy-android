import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
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

rootProject.name = "AndyAndroid"
include(":app")
include(":core:device-identity")
include(":data:device-identity")
include(":data:client-session")
include(":core:human-identity")
include(":sdk:client-api")
include(":integrations:google-identity")
include(":features:onboarding")
