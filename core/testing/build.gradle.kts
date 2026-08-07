plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nanobotkt.core.testing"
    compileSdk { version = release(37) }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
    api(libs.mockwebserver)
    api(libs.turbine)
    implementation(libs.kotlinx.serialization.json)
}
