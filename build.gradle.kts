// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.6.0" apply false
    // LiteRT-LM's litertlm-android artifact ships Kotlin metadata built with
    // Kotlin 2.3.x; a compiler older than that can't read it ("Module was
    // compiled with an incompatible version of Kotlin"), so this needs to
    // stay at or above whatever version litertlm-android currently requires.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
