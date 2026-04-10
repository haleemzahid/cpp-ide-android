// Top-level build file. AGP 9.0+ provides Kotlin support built-in, so we
// no longer apply org.jetbrains.kotlin.android explicitly. The compose
// compiler plugin still needs to be declared separately.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
