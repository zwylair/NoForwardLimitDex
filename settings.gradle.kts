pluginManagement {
    includeBuild(providers.gradleProperty("buildlogic.dir").getOrElse("../DexBuildLogic"))

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("zwy.dex-settings")
}

rootProject.name = "NoForwardLimitDex"
include("dex")
