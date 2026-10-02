import java.util.Properties
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("com.android.application")
    id("jacoco")
    id("org.jetbrains.kotlin.android")
    // Apply the Compose Compiler plugin
    alias(libs.plugins.kotlin.compose)

    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")

}

jacoco { toolVersion = "0.8.12" }

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    maxParallelForks = 1
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED"
    )
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// Every application package is measured, including UI, services and repositories.
// Only build-generated resource identifiers/configuration are omitted.
val regressionClasses = files(
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.class")
    },
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.class")
    }
)
val regressionExecution = layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")

tasks.register<JacocoReport>("unitRegressionCoverage") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(regressionClasses)
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(regressionExecution)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("verifyFullUnitRegressionCoverage") {
    dependsOn("unitRegressionCoverage")
    classDirectories.setFrom(regressionClasses)
    executionData.setFrom(regressionExecution)
    violationRules {
        rule {
            listOf("LINE", "BRANCH", "METHOD", "CLASS", "INSTRUCTION").forEach { metric ->
                limit {
                    counter = metric
                    value = "COVEREDRATIO"
                    minimum = "1.0000".toBigDecimal()
                }
            }
        }
    }
}

// Whole-application target: the same unfiltered denominator as the HTML report.
// Run explicitly until the missing Android/UI tests make this gate pass.
tasks.register<JacocoCoverageVerification>("verify90PercentUnitLineCoverage") {
    dependsOn("unitRegressionCoverage")
    classDirectories.setFrom(regressionClasses)
    executionData.setFrom(regressionExecution)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.9000".toBigDecimal()
            }
        }
    }
}

tasks.register<JacocoCoverageVerification>("verifyProgressBoundaryRegressionCoverage") {
    dependsOn("unitRegressionCoverage")
    classDirectories.setFrom(regressionClasses)
    executionData.setFrom(regressionExecution)
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf("com.saab.tv.ui.player.ProgressSnapshotPolicy")
            listOf("LINE", "BRANCH", "METHOD", "INSTRUCTION").forEach { metric ->
                limit {
                    counter = metric
                    value = "COVEREDRATIO"
                    minimum = "1.0".toBigDecimal()
                }
            }
        }
    }
}

// Protect the completed policy regression suite, including nested comparator classes.
tasks.register<JacocoCoverageVerification>("verifyCorePolicyRegressionCoverage") {
    dependsOn("unitRegressionCoverage")
    classDirectories.setFrom(regressionClasses)
    executionData.setFrom(regressionExecution)
    val policyClasses = listOf(
        "com.saab.tv.ui.details.SeriesActionLabelKt*",
        "com.saab.tv.data.profile.ProfilePin*",
        "com.saab.tv.data.profile.ProfileOnboardingDefaultsKt*",
        "com.saab.tv.ui.trailer.TrailerPreviewPolicy*",
        "com.saab.tv.data.cache.SeekThumbnailWorkerMemoryPolicy*",
        "com.saab.tv.data.cache.ThumbnailWorkPolicy*",
        "com.saab.tv.data.cache.ThumbnailTimelinePolicy*",
        "com.saab.tv.ui.home.ContinueResumePolicy*",
        "com.saab.tv.ui.home.HomePreviewMetadataPolicy*",
        "com.saab.tv.DiagnosticRecordingPolicy*",
        "com.saab.tv.ui.watchlist.WatchlistFocusPolicy*",
        "com.saab.tv.ui.player.base.PlaybackReliabilityStateMachine*",
        "com.saab.tv.ui.player.base.SubtitleTrackPreference*",
        "com.saab.tv.ui.player.base.SubtitleSelectionPolicy*",
        "com.saab.tv.ui.player.base.PlaybackBufferRecoveryPolicy*",
        "com.saab.tv.ui.player.base.AutoplayNextEpisodePolicy*",
        "com.saab.tv.ui.player.base.AdaptiveBufferPolicy*",
        "com.saab.tv.ui.player.base.SeekThumbnailCarouselPolicy*",
        "com.saab.tv.ui.player.base.VideoJitterRecoveryPolicy*",
        "com.saab.tv.ui.common.ListStateSafety*",
        "com.saab.tv.ui.player.ProgressSnapshotPolicy*",
        "com.saab.tv.ui.player.WatchProgressPolicy*",
    )
    doFirst {
        policyClasses.forEach { pattern ->
            val relativePath = pattern.removeSuffix("*").replace('.', '/') + ".class"
            check(layout.buildDirectory.file("tmp/kotlin-classes/debug/$relativePath").get().asFile.exists()) {
                "Required regression policy disappeared: $pattern"
            }
        }
    }
    violationRules {
        rule {
            element = "CLASS"
            includes = policyClasses
            listOf("LINE", "BRANCH", "METHOD", "CLASS", "INSTRUCTION").forEach { metric ->
                limit {
                    counter = metric
                    value = "COVEREDRATIO"
                    minimum = "1.0000".toBigDecimal()
                }
            }
        }
    }
}

// Read ACRA config from local.properties (keeps secrets out of version control)
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) load(localPropsFile.inputStream())
}
val acraUrl: String = localProperties.getProperty("acra.url", "")
val acraToken: String = localProperties.getProperty("acra.token", "")
val tmdbApiKey: String = localProperties.getProperty("tmdb.api_key", "")
val traktClientId: String = localProperties.getProperty("TRAKT_CLIENT_ID", "")
val traktClientSecret: String = localProperties.getProperty("TRAKT_CLIENT_SECRET", "")

// Release signing stays outside source control. Values may come from
// release-signing.properties or CI environment variables.
val releaseSigningProperties = Properties().apply {
    val file = rootProject.file("release-signing.properties")
    if (file.exists()) load(file.inputStream())
}
fun releaseSigningValue(property: String, environment: String): String? =
    releaseSigningProperties.getProperty(property)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: System.getenv(environment)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStorePath = releaseSigningValue("storeFile", "SAABTV_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "SAABTV_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "SAABTV_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "SAABTV_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.saab.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.saab.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 96
        versionName = "0.1.92"

        // GitHub repository for auto-update system
        buildConfigField("String", "GITHUB_OWNER", "\"saabtv-gf\"")
        buildConfigField("String", "GITHUB_REPO", "\"saabtv-core\"")

        // ACRA crash reporting (loaded from local.properties)
        buildConfigField("String", "ACRA_URL", "\"$acraUrl\"")
        buildConfigField("String", "ACRA_TOKEN", "\"$acraToken\"")

        // TMDB API (loaded from local.properties)
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")

        // Trakt API (loaded from local.properties)
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktClientId\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"$traktClientSecret\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Native playback components dominate the universal APK. Build one APK per
    // CPU architecture so ARM64 TVs do not also download the 32-bit stack.
    splits {
        abi {
            isEnable = true
            reset()
            if (providers.gradleProperty("saab32BitOnly").orNull == "true") {
                include("armeabi-v7a")
            } else {
                include("arm64-v8a", "armeabi-v7a")
            }
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test"
            resValue("string", "app_name", "Saab TV Test")
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Compose Compiler configuration for optimal performance
composeCompiler {
    // Enable strong skipping mode for more efficient recomposition
    // Skips recomposition when parameters are stable even if equals() isn't overridden
    enableStrongSkippingMode = true

    // Enable intrinsic remember optimization
    enableIntrinsicRemember = true

    // Stability configuration: tells the compiler which classes are effectively immutable
    // so it can skip recomposition when their instances haven't changed
    stabilityConfigurationFile = project.layout.projectDirectory.file("compose_stability_config.conf")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

dependencies {
    // 0. ASS/SSA subtitle renderer
    implementation(libs.assrender)

    // 1. Android TV UI (Compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)


    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.catalog.androidx.lifecycle.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // 2. Networking
    implementation(libs.catalog.com.squareup.retrofit2.retrofit)
    implementation(libs.catalog.com.squareup.retrofit2.converter.gson)
    // 4. Image Loading
    implementation(libs.catalog.io.coil.kt.coil.compose)

    // 5. Database
    implementation(libs.catalog.androidx.room.room.runtime)
    implementation(libs.catalog.androidx.room.room.ktx)
    implementation(libs.androidx.compose.animation.core)
    kapt(libs.catalog.androidx.room.room.compiler)

    // 6. Dependency Injection
    implementation(libs.catalog.com.google.dagger.hilt.android)
    kapt(libs.catalog.com.google.dagger.hilt.android.compiler)
    implementation(libs.catalog.androidx.hilt.hilt.navigation.compose)

    // 7. Video Player
    implementation(project(":playbackcore"))
    implementation(files("../playbackcore/libs/lib-exoplayer-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-av1-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-ffmpeg-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-iamf-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-mpegh-release.aar"))
    // Persistent secondary decoder used only by the isolated seek-thumbnail worker.
    implementation(files("libs/libmpv-saab-namespaced.aar"))

    // 8. Testing & Debugging
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.catalog.androidx.compose.material3.material3)
    implementation(libs.catalog.androidx.compose.material.material.icons.extended)

    // OkHttp is already available via Retrofit, but declare explicitly for TorrServer API
    implementation(libs.okhttp.client)
    implementation(libs.newpipe.extractor) {
        // The extractor calls Rhino Context directly in interpreted mode.
        // Its optional desktop javax.script adapter is not an Android runtime.
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    coreLibraryDesugaring(libs.android.desugar.jdk.libs.nio)

    // --- LOCAL WEB SERVER (used by remote input hub) ---

    // --- QR CODE GENERATION ---
    implementation(libs.catalog.com.google.zxing.core)

    // --- ENCRYPTED SHARED PREFERENCES ---
    implementation(libs.catalog.androidx.security.security.crypto)

    // --- CRASH REPORTING (ACRA) ---
    implementation(libs.catalog.ch.acra.acra.http)
    implementation(libs.catalog.ch.acra.acra.toast)

    testImplementation(libs.catalog.junit.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.catalog.androidx.test.runner)
    androidTestImplementation(libs.catalog.androidx.test.core.ktx)
    androidTestImplementation(libs.catalog.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.catalog.androidx.test.rules)
    androidTestImplementation(libs.catalog.androidx.room.room.testing)
    androidTestImplementation(libs.catalog.androidx.sqlite.sqlite.framework)

}

tasks.register("generateSbom") {
    group = "verification"
    description = "Generates a CycloneDX dependency inventory for the release runtime."
    val outputFile = layout.buildDirectory.file("reports/sbom/saab-tv.cdx.json")
    outputs.file(outputFile)
    doLast {
        val components = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .distinctBy { "${it.group}:${it.name}:${it.version}" }
            .sortedBy { "${it.group}:${it.name}:${it.version}" }
        fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
        val jsonComponents = components.joinToString(",\n") { id ->
            """    {"type":"library","group":"${id.group.jsonEscape()}","name":"${id.name.jsonEscape()}","version":"${id.version.jsonEscape()}","bom-ref":"${"${id.group}:${id.name}:${id.version}".jsonEscape()}"}"""
        }
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(
            """{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "version": 1,
  "metadata": {"component":{"type":"application","name":"Saab TV","version":"${android.defaultConfig.versionName}"}},
  "components": [
$jsonComponents
  ]
}
"""
        )
    }
}
