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

        maven {
            val telegramPath = providers.gradleProperty("telegram.dir").get()
            url = uri("$telegramPath/Tools/maven")
        }
    }
}

rootProject.name = "NoForwardLimitDex"
include("dex")
