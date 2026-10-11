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

rootProject.name = "finder-plus"

include(
    ":app",
    ":core-model",
    ":core-db",
    ":core-media",
    ":engine-index",
    ":engine-search",
    ":ai-vision",
    ":ai-speech",
    ":ai-text",
)
