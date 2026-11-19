// AGP 9 applies Kotlin support itself, so there is no separate
// org.jetbrains.kotlin.android plugin here. See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
