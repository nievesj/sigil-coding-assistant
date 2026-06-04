plugins {
    id("org.jetbrains.intellij.platform") version("2.16.0")
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
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
    // ACP SDK
    implementation(libs.acp.sdk)

    // Ktor HTTP client — exclude coroutines (IntelliJ Platform bundles its own patched fork)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // KotlinX serialization (safe — no coroutine transitive deps)
    implementation(libs.kotlinx.serialization.json)

    // KotlinX collections (safe — no coroutine transitive deps)
    implementation(libs.kotlinx.collections.immutable)

    // compileOnly for coroutines — compile against platform's version, don't bundle
    compileOnly(libs.kotlinx.coroutines.core)

    // Logging — IntelliJ Platform bundles SLF4J; compile-only, exclude from plugin zip
    compileOnly(libs.slf4j.api)
    implementation(libs.logback.classic) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    // Metrics
    implementation(libs.micrometer.core)

    // Testing
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.engine)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.wiremock)

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")

        // Jewel/Compose bundled modules
        composeUI()

        // Jewel Markdown (not included in composeUI)
        bundledModule("intellij.platform.jewel.markdown.core")
        bundledModule("intellij.platform.jewel.markdown.ideLafBridgeStyling")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmTables")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmAlerts")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmStrikethrough")
        bundledModule("intellij.platform.jewel.markdown.extensions.autolink")
    }
}

// Global exclusions: catch anything missed by per-dependency excludes
// Must NOT use configureEach — these must remain on compileClasspath for the compiler
configurations.runtimeClasspath {
    // Coroutines — IDE bundles its own patched fork; must not be in plugin JAR
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-reactive")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-reactor")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")

    // Kotlin stdlib — IDE bundles its own; duplicates cause classloader conflicts
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")

    // SLF4J — IDE bundles its own
    exclude(group = "org.slf4j", module = "slf4j-api")

    // JetBrains Annotations — IDE bundles its own
    exclude(group = "org.jetbrains", module = "annotations")

    // Netty — no longer needed (Java engine uses java.net.http.HttpClient)
    exclude(group = "io.netty", module = "netty-common")
    exclude(group = "io.netty", module = "netty-buffer")
    exclude(group = "io.netty", module = "netty-transport")
    exclude(group = "io.netty", module = "netty-handler")
    exclude(group = "io.netty", module = "netty-codec")
    exclude(group = "io.netty", module = "netty-codec-http")
    exclude(group = "io.netty", module = "netty-codec-http2")
    exclude(group = "io.netty", module = "netty-resolver")
    exclude(group = "io.netty", module = "netty-resolver-dns")
    exclude(group = "io.netty", module = "netty-codec-dns")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName").get()
        version = providers.gradleProperty("pluginVersion").get()
        ideaVersion {
            sinceBuild = "261"
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

tasks.test {
    useJUnitPlatform()
}
