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

        // exterahook is published only to the maven mirror inside the host checkout
        maven {
            url = uri("${providers.gradleProperty("host.dir").get()}/Tools/maven")
            content { includeGroupByRegex("""dev\.exterahook(\..+)?""") }
        }
    }
}

rootProject.name = "NoForwardLimitDex"
include("dex")
