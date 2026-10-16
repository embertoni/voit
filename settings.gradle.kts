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
    }
}

rootProject.name = "voit"

include(
    ":app",
    ":core:domain",
    ":core:data",
    ":core:voice",
    ":core:interpretation",
    ":core:actions",
    ":core:design",
    ":platform:android",
    ":feature:notes",
    ":feature:tasks",
    ":feature:commands",
    ":feature:settings",
    ":feature:simulator",
)
