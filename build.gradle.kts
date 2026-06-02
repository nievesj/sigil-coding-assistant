plugins {
    id("org.jetbrains.intellij.platform") version("2.16.0")
    kotlin("jvm") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName").get()
        version = providers.gradleProperty("pluginVersion").get()
        ideaVersion {
            sinceBuild = "261.0"
            untilBuild = "263.*"
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.runIde {
    jvmArgs = listOf(
        "-Dopencode.platform.version=${providers.gradleProperty("platformVersion").get()}",
        "-Didea.auto.reload.plugins=true"
    )
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get()))
    }
}
