import com.android.build.api.dsl.CommonExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

@Suppress("UnstableApiUsage")
extensions.configure<CommonExtension> {
    // Compose 编译器插件与 Android Compose build feature 必须同时启用；
    // 统一放在 convention plugin 中，避免模块只应用其中一半导致配置期失败。
    buildFeatures.compose = true
}
