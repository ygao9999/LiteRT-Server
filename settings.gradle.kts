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
        maven { url = uri("https://storage.googleapis.com/download.tensorflow.org/maven") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LiteRTServer"
include(":app", ":client")
