// settings.gradle.kts — Synctuary Android client
//
// Single-module project for now (`:app`). Will split into network /
// crypto / data submodules once the surface stabilizes (~v0.5).

pluginManagement {
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
    // Fail-fast: any dependency added at the project level is a bug;
    // every coordinate must live in libs.versions.toml.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "synctuary"
include(":app")
