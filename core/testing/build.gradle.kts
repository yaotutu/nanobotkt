plugins {
    id("nanobot.android.library")
    id("nanobot.kotlin.serialization")
}

android {
    namespace = "com.nanobotkt.core.testing"



}

dependencies {
    api(project(":core:model"))
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
    api(libs.mockwebserver)
    api(libs.turbine)
    implementation(libs.kotlinx.serialization.json)
}
