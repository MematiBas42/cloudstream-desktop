pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "cloudstream-desktop"

include(":library")
project(":library").projectDir = file("upstream/library")

include(":common")
project(":common").projectDir = file("common")

include(":android-shims")
project(":android-shims").projectDir = file("android-shims")

include(":plugin-runtime")
project(":plugin-runtime").projectDir = file("plugin-runtime")

include(":player-mpv")
project(":player-mpv").projectDir = file("player-mpv")

include(":desktop-app")
project(":desktop-app").projectDir = file("desktop-app")

include(":tests")
project(":tests").projectDir = file("tests")
