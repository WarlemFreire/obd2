pluginManagement {
    repositories {
        // O repositorio do Google so serve os grupos Android; assim o modulo
        // core compila mesmo onde dl.google.com nao e acessivel.
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
    plugins {
        id("com.android.application") version "9.5.1"
        // AGP 9 compila Kotlin sozinho (built-in Kotlin): sem kotlin-android.
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "obd2"

include(":core")

// O modulo Android so entra onde existe Android SDK (runner do GitHub, Android
// Studio). Sem SDK, `gradle :core:test` continua funcionando.
val temSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (temSdk) include(":app")
