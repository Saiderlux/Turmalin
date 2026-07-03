// Configuración raíz del proyecto Turmalin.
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Repositorio de Google Maven: requerido para androidx.ink y el resto de androidx.
        google()
        mavenCentral()
    }
}

rootProject.name = "Turmalin"
include(":app")
