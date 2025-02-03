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
        maven { // for com.github.chrisbanes:PhotoView
            url = uri("https://www.jitpack.io")
        }

        val localSdk = File("${providers.gradleProperty("LinphoneSdkBuildDir").get()}/maven_repository/org/linphone/linphone-sdk-android/maven-metadata.xml")
        if (localSdk.exists()) {
            val localSdkPath = providers.gradleProperty("LinphoneSdkBuildDir").get()
            println("Using locally built SDK from maven repository at ${localSdkPath}/maven_repository/")
            maven {
                name = "local linphone-sdk maven repository"
                url = uri(
                    "file://${localSdkPath}/maven_repository/"
                )
                content {
                    includeGroup("org.linphone")
                }
            }
        } else {
            maven {
                println("Using CI built SDK from maven repository at https://linphone.org/maven_repository")
                name = "linphone.org maven repository"
                url = uri("https://linphone.org/maven_repository")
                content {
                    includeGroup("org.linphone")
                }
            }
        }
    }
}

rootProject.name = "Linphone"
include(":app")
