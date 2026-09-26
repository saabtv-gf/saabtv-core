pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SaabTv"
include(":app")
include(":playbackcore")
include(":assrender")
include(":benchmark")
project(":assrender").projectDir = file("../assrender/assrender")
