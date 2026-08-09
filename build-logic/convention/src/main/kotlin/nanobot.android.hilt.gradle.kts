plugins {
    id("com.google.dagger.hilt.android")
    // Hilt 代码生成依赖 KSP；由 convention 统一应用，模块不再重复声明两个插件。
    id("com.google.devtools.ksp")
}
