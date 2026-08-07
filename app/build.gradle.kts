plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

fun String.asBuildConfigString(): String = "\"${replace("\"", "\\\"")}\""

val configuredServerUrl = providers.gradleProperty("NANOBOT_SERVER_URL")
    .orElse(providers.environmentVariable("NANOBOT_SERVER_URL"))

android {
    namespace = "com.nanobotkt"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.nanobotkt"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "NANOBOT_SERVER_URL", configuredServerUrl.getOrElse("http://localhost:8765").asBuildConfigString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "NANOBOT_SERVER_URL", configuredServerUrl.getOrElse("http://192.168.55.147:8765").asBuildConfigString())
        }
    }

    androidResources {
        localeFilters += setOf("en", "es", "fr", "id", "ja", "ko", "pt-rBR", "vi", "zh-rCN", "zh-rTW")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    testImplementation(project(":core:testing"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}




