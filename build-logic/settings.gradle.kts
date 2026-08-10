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
        gradlePluginPortal()
    }

    // included build 不会自动继承主工程的 version catalog，这里显式复用同一份目录，
    // 防止 convention plugin 与业务模块出现 AGP、Kotlin 或 KSP 版本漂移。
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "nanobotkt-build-logic"
include(":convention")
