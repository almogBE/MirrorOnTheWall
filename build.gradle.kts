// Top-level build file. Module configuration lives in each module's build.gradle.kts.
//
// AGP 9 ships built-in Kotlin support, so the Android modules do NOT apply
// org.jetbrains.kotlin.android. Declaring the Kotlin JVM plugin here (for :common)
// also puts the catalog's KGP version on the build classpath, which AGP's built-in
// Kotlin then uses. This keeps one Kotlin version across all modules and the
// Compose compiler plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
