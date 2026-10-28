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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (tesseract4android-openmp) публикуется только на JitPack.
        // Аудит 2026-07-02 (supply chain): JitPack ограничен ТОЛЬКО этой группой —
        // никакая другая зависимость не может незаметно приехать с jitpack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "socialsphere"

include(":app")
