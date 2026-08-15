import java.util.Properties
import org.gradle.api.provider.Provider

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

fun String.asBuildConfigString(): String = "\"${replace("\"", "\\\"")}\""

// 版本号集中放在仓库根目录，避免把发布版本散落在 Gradle 配置和 workflow 中。
// 本地在 dev/main 分支执行 scripts/release.sh dev/release 会先更新并提交该文件；CI 只读取提交中的版本构建。
val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties().apply {
    if (versionPropertiesFile.isFile) {
        versionPropertiesFile.inputStream().use(::load)
    }
}
val appVersionName: Provider<String> = providers.gradleProperty("APP_VERSION_NAME")
    .orElse(versionProperties.getProperty("VERSION_NAME", "0.1.0"))
val appVersionCode: Provider<String> = providers.gradleProperty("APP_VERSION_CODE")
    .orElse(versionProperties.getProperty("VERSION_CODE", "1"))

// 本地调试与正式构建都默认直连局域网 Gateway，避免 Android 模拟器把 localhost 解析到自身，
// 也避免依赖重启模拟器后会丢失的 adb reverse 映射。需要连接其他环境时仍可通过 Gradle 属性或环境变量覆盖。
val defaultServerUrl = "http://192.168.55.147:8765"
val configuredServerUrl = providers.gradleProperty("NANOBOT_SERVER_URL")
    .orElse(providers.environmentVariable("NANOBOT_SERVER_URL"))

android {
    namespace = "com.nanobotkt"

    defaultConfig {
        applicationId = "com.nanobotkt"
        targetSdk = 37
        // versionCode 必须是单调递增整数；脚本会和 0.1.x 的补丁版本一起递增。
        versionCode = appVersionCode.get().toInt()
        versionName = appVersionName.get()
        vectorDrawables.useSupportLibrary = true
    }

    // ============================================================
    // 签名配置（CI 自动发布使用）
    // 仅当检测到 keystore 文件与密码环境变量时，release 才使用正式签名；
    // 本地开发缺少这些变量时保持原行为（release 未签名）。
    // CI 中由 workflow 写入 keystore.jks 并注入环境变量。
    // ============================================================
    val keystoreFileEnv = System.getenv("KEYSTORE_FILE")
    val keystorePasswordEnv = System.getenv("KEYSTORE_PASSWORD")
    val keyAliasEnv = System.getenv("KEY_ALIAS")
    val keyPasswordEnv = System.getenv("KEY_PASSWORD")
    val releaseKeystoreFile = keystoreFileEnv?.let { rootProject.file(it) }
        ?: rootProject.file("keystore.jks")
    // 环境变量存在但为空时仍视为未配置，避免本地 shell 或 CI 把空密码误判成可用签名。
    val hasReleaseKeystore = releaseKeystoreFile.isFile &&
        !keystorePasswordEnv.isNullOrBlank() &&
        !keyAliasEnv.isNullOrBlank() &&
        !keyPasswordEnv.isNullOrBlank()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = keystorePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "NANOBOT_SERVER_URL", configuredServerUrl.getOrElse(defaultServerUrl).asBuildConfigString())
        }
        release {
            // 正式版必须使用稳定的 release keystore；没有配置时仍允许本地生成未签名 APK，
            // 但正式发布 workflow 会在构建前主动检查 Secrets，避免误发布不可更新的 APK。
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "NANOBOT_SERVER_URL", configuredServerUrl.getOrElse(defaultServerUrl).asBuildConfigString())
        }
        create("dev") {
            // dev 与正式版故意使用同一个 applicationId（com.nanobotkt）和同一份稳定签名。
            // 这样测试版和正式版都能在 versionCode 递增时直接覆盖旧 APK，不再维护两套安装渠道。
            // dev 仍保留 -dev 版本名后缀，方便用户识别当前安装的是测试构建。
            // 没有 Secrets 的本地构建/PR 仍允许回退到 debug keystore，但这类 APK 只能做验证，
            // 不能替代真正发布包，否则用户会遇到“签名不一致，无法覆盖安装”。
            initWith(getByName("release"))
            versionNameSuffix = "-dev"
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            matchingFallbacks += listOf("release")
        }
    }

    // ============================================================
    // 按 CPU 架构拆分 APK，并额外产出 universal 通用包
    // assembleDev / assembleRelease 会分别生成：
    //   app-{armeabi-v7a|arm64-v8a|x86|x86_64}-{variant}.apk
    //   app-universal-{variant}.apk
    // ============================================================
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    androidResources {
        // 设备可能返回带脚本的 BCP-47 Locale（例如 zh-Hans-SG），只保留 zh-rCN/zh-rTW 会在 APK 打包时过滤掉对应资源，导致界面回退到英文。
        localeFilters += setOf(
            "en",
            "es",
            "fr",
            "id",
            "ja",
            "ko",
            "pt-rBR",
            "vi",
            "zh",
            "zh-rCN",
            "zh-rTW",
            "b+zh+Hans",
            "b+zh+Hans+SG",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:transport"))
    implementation(project(":core:persistence"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:sidebar"))
    implementation(project(":feature:workspaces"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:apps"))
    implementation(project(":feature:skills"))
    implementation(project(":feature:automations"))
    implementation(project(":feature:channels"))
    implementation(project(":feature:security"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.bundles.unit.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}




