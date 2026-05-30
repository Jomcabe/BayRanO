import java.util.Properties

pluginManagement {
    repositories {
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

// Load secrets from local.properties (gitignored) so the Meta toolkit's
// GitHub Packages repo can be authenticated. Falls back to environment
// variables (handy for CI) when the file or a key is absent.
val localProperties = Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(key: String): String? =
    localProperties.getProperty(key) ?: System.getenv(key)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Meta Wearables Device Access Toolkit is published to GitHub Packages.
        // GitHub Packages requires authentication even for public artifacts.
        maven {
            name = "MetaWearablesGitHubPackages"
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = secret("GITHUB_USER")
                password = secret("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "BayRanO"
include(":app")
