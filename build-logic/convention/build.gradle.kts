plugins {
    `kotlin-dsl`
}

dependencies {
    // 这些插件依赖只供 convention plugin 编译使用，不会直接打进任何 APK。
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.hilt.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
}
