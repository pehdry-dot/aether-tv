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
        // Google-hosted mirror of Maven Central. Listed first because some build sandboxes block the CDN that
        // Maven Central redirects large artifacts to. Safe to remove in normal environments.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
    }
}
rootProject.name = "AetherTV"
include(":app")
