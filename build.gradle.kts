// Top-level build file — configuration is per-module.
//
// AGP 9 has built-in Kotlin support, so `org.jetbrains.kotlin.android` is no
// longer applied (and is incompatible with the new DSL). AGP pins KGP/KSP to its
// own baseline, so upgrading them is done here on the buildscript classpath.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
