pluginManagement {
    // 将构建约定作为 included build 管理，避免把 Gradle DSL 逻辑散落到每个模块。
    includeBuild("build-logic")
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
    ":core:workspace-contract",
    ":core:network",
    ":core:transport",
    ":core:persistence",
    ":core:designsystem",
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

