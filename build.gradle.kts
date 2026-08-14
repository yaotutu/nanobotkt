import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import java.io.File
import org.gradle.api.JavaVersion
import org.gradle.kotlin.dsl.configure

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * 统一所有 Android 模块的基础构建参数。
 *
 * 这些配置原本藏在单独的 convention build 中；项目规模并不需要再维护一层
 * included build，因此直接放在根脚本，通过插件出现后再配置对应 Android 扩展。
 * applicationId、版本号、ABI、签名和模块专属依赖仍由各模块自己声明。
 */
fun CommonExtension.configureNanobotAndroid() {
    compileSdk = 37
    defaultConfig.minSdk = 24
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17

    // Repository 和 ViewModel 的 JVM 测试需要 Android resource 合并能力，但不应依赖设备。
    testOptions.unitTests.isIncludeAndroidResources = true
}

subprojects {
    // Android 插件由各模块直接声明；插件加载完成后统一补充公共 Android 配置。
    plugins.withId("com.android.application") {
        extensions.configure<CommonExtension> {
            configureNanobotAndroid()
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            configureNanobotAndroid()
            // 保留原 convention plugin 为每个 AAR 注册 consumer Proguard 规则的行为。
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
    }

    // Compose 编译器插件和 Android Compose build feature 必须一起启用。
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<CommonExtension> {
            buildFeatures.compose = true
        }
    }

    tasks.withType<Test>().configureEach {
        val testSourceDirectory = project.layout.projectDirectory.dir("src/test").asFile
        val hasJvmTestSources = testSourceDirectory.walkTopDown().any { file ->
            file.isFile && file.extension in setOf("kt", "java")
        }

        if (!hasJvmTestSources) {
            failOnNoDiscoveredTests.set(false)
        }
    }
}
