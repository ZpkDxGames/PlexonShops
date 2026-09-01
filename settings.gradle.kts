pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://repo.helpch.at/releases/") {
            name = "placeholderapi"
        }
        maven("https://jitpack.io") {
            name = "jitpack"
        }
    }
}

rootProject.name = "PlexonShops"
