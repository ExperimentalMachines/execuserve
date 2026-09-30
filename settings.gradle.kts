pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "ExecuServe"

// Kotlin Multiplatform: what the server does. Compiles for Android, the JVM and iOS.
include(":shared:api")
include(":shared:prompt")
include(":shared:engine")
include(":shared:catalog")
include(":shared:server")
include(":shared:host")

// Native: how Android lets it.
include(":android:executorch")
include(":android:app")

// The same server on a desktop JVM, over a scripted runtime.
include(":jvm:testing")
include(":jvm:devserver")
