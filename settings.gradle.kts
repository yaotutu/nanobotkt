pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nanobotkt"

include(
    ":app",
    ":core:model",
    ":core:network",
    ":core:transport",
    ":core:persistence",
    ":core:designsystem",
    ":core:testing",
    ":feature:auth",
    ":feature:chat",
    ":feature:sidebar",
    ":feature:workspaces",
    ":feature:settings",
    ":feature:apps",
    ":feature:skills",
    ":feature:automations",
    ":feature:channels",
    ":feature:security",
)

