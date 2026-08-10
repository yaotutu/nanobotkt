package com.nanobotkt.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

/**
 * 统一 NanobotKT Android 模块的基础配置。
 *
 * 这里仅放所有模块都必须一致的构建约束；applicationId、version、ABI 拆分、签名和
 * Feature 专属依赖仍由具体模块声明，避免 convention plugin 反过来吞掉业务差异。
 *
 * AGP 9.x 的 CommonExtension 已经不再携带旧版的泛型参数，因此这里直接使用无泛型
 * API，并通过属性访问 DSL，避免依赖仅在模块脚本中生成的 Kotlin DSL block accessor。
 */
internal fun CommonExtension.configureNanobotAndroid() {
    // 使用属性写法而不是旧的 release(...) DSL，兼容 AGP 9.x 并保持版本约束集中。
    compileSdk = 37
    defaultConfig.minSdk = 24
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17

    // Repository 和 ViewModel 的 JVM 测试需要 Android resource 合并能力，但不应依赖设备。
    testOptions.unitTests.isIncludeAndroidResources = true
}
