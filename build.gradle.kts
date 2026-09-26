// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Apply the new compose plugin
    alias(libs.plugins.kotlin.compose) apply false

    // Hilt (still manual as it is not in the plugin block of toml yet)
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}
