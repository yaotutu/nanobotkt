import com.android.build.api.dsl.LibraryExtension
import com.nanobotkt.buildlogic.configureNanobotAndroid
import org.gradle.kotlin.dsl.configure

plugins {
    id("com.android.library")
}

extensions.configure<LibraryExtension> {
    configureNanobotAndroid()
    defaultConfig {
        // 所有 Android library 都保留同名 consumer 规则文件，保持迁移前的 AAR 元数据行为。
        consumerProguardFiles("consumer-rules.pro")
    }
}
