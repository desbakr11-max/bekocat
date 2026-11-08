pluginManagement {
    repositories {
        google()
        maven("https://repo1.maven.org/maven2/") // Maven Central
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://repo1.maven.org/maven2/") // Maven Central
    }
}

rootProject.name = "Bekocat"

include(":app")
include(":core:timeline")
include(":engine")
include(":ai")
