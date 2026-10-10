pluginManagement {
    repositories {
        // Resolve shared plugins from their official publisher before Android-only artifacts.
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "lezi"

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:ui")
include(":designsystem")
include(":domain")
include(":sync")
include(":feature:onboarding")
include(":feature:log")
include(":feature:timer")
include(":feature:family")
include(":feature:settings")
include(":feature:summary")
include(":feature:growth")
include(":feature:export")
include(":feature:search")
include(":feature:widget")
