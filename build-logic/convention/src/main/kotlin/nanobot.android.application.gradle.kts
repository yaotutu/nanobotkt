import com.android.build.api.dsl.ApplicationExtension
import com.nanobotkt.buildlogic.configureNanobotAndroid
import org.gradle.kotlin.dsl.configure

plugins {
    id("com.android.application")
}

extensions.configure<ApplicationExtension> {
    configureNanobotAndroid()
}
