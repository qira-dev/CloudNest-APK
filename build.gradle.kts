// Root build file. Versions pinned here are the known-good combo:
// Gradle 8.9 (wrapper), AGP 8.7.3, Kotlin 2.0.21, compileSdk 35.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
