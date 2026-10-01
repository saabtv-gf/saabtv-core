plugins {
    id("com.android.library")
}

android {
    namespace = "com.saab.tv.playbackcore"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "MissingTranslation"
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    api(libs.catalog.androidx.media3.media3.common)
    api(libs.catalog.androidx.media3.media3.container)
    api(libs.catalog.androidx.media3.media3.datasource)
    api(libs.catalog.androidx.media3.media3.datasource.okhttp)
    api(libs.catalog.androidx.media3.media3.decoder)
    api(libs.catalog.androidx.media3.media3.exoplayer.dash) {
        exclude(group = "androidx.media3", module = "media3-exoplayer")
    }
    api(libs.catalog.androidx.media3.media3.exoplayer.hls) {
        exclude(group = "androidx.media3", module = "media3-exoplayer")
    }
    api(libs.catalog.androidx.media3.media3.extractor)
    api(libs.catalog.androidx.media3.media3.ui)
}
