plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nanobotkt.feature.chat"



}

dependencies {
    implementation(project(":core:workspace-contract"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:transport"))
    implementation(project(":core:persistence"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    // ToolProgressEvent 的公开字段直接暴露 JsonElement；聊天 UI 会读取 result/error 来渲染状态，
    // 因而本模块必须显式声明 JSON 运行时，不能依赖 core:model 的非传递 implementation 细节。
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    testImplementation(libs.bundles.unit.test)
    // Repository 契约测试需要直接构造 Json，以验证服务端分页响应中的未知字段兼容性。
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Composer 仪器测试使用独立 Compose Host Activity 渲染真实组件，不依赖登录态或 Gateway 数据；
    // manifest 仅进入 debug 变体，避免把测试 Activity 带入正式包。
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

