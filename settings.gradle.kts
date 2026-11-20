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
        // Nur für das hCaptcha-SDK (Anmelde-Captcha), nichts anderes wird von dort geladen.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.hCaptcha.hcaptcha-android-sdk") }
        }
    }
}

rootProject.name = "PWDR"
include(":app")
