plugins {
    id("nanobot.android.library")
    id("nanobot.kotlin.serialization")
}

android {
    namespace = "com.nanobotkt.core.model"



}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    testImplementation(libs.junit4)
}
