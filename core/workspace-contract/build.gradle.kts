plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nanobotkt.core.workspace"


}

dependencies {
    // 契约对外暴露 WorkspacesPayload 和 StateFlow，因此使用 api 保证下游模块能够编译。
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
