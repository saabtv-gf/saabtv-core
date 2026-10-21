plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.saab.tv.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulator runs are wiring smoke tests only; do not use their timings as device data.
        if (providers.gradleProperty("saabAllowEmulatorBenchmark").isPresent) {
            testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        }
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.catalog.androidx.test.ext.junit)
    implementation(libs.catalog.androidx.test.uiautomator.uiautomator)
    implementation(libs.catalog.androidx.benchmark.benchmark.macro.junit4)
}
