plugins {
    id("nanobot.android.library")
    id("nanobot.kotlin.serialization")
    id("nanobot.android.hilt")
}

android {
    namespace = "com.nanobotkt.core.persistence"



}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
