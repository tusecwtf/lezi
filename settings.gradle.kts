pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
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
