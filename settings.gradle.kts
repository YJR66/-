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
        // XposedBridgeApi artifact (de.robv.android.xposed:api:82)
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "VirtualCamera"
include(":app")
