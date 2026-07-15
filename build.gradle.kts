import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

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
    testImplementation(libs.mockk)

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

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
        }
        // Change notes for JetBrains Marketplace — read from file path passed via Gradle property
        changeNotes = providers.gradleProperty("changeNotesFile").map { path ->
            val f = file(path)
            if (f.exists()) f.readText() else ""
        }.orElse("")
    }

    pluginVerification {
        ides {
            recommended()
            create(IntelliJPlatformType.PyCharm, "2026.1")
            create(IntelliJPlatformType.WebStorm, "2026.1")
            create(IntelliJPlatformType.GoLand, "2026.1")
            create(IntelliJPlatformType.CLion, "2026.1")
            create(IntelliJPlatformType.Rider, "2026.1")
            create(IntelliJPlatformType.RubyMine, "2026.1")
            create(IntelliJPlatformType.RustRover, "2026.1")
            create(IntelliJPlatformType.PhpStorm, "2026.1")
            create(IntelliJPlatformType.DataGrip, "2026.1")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.gradleProperty("publishToken")
        // The plugin ID from plugin.xml — used by the publishPlugin task
        // Uncomment and set if not using the default from plugin.xml
        // pluginId = "com.sigil.plugin"
        // Hidden mode: when true, the published version is not publicly visible
        // after approval (accessible only via direct link). Controlled by CI
        // via -Phidden=true so local builds are unaffected.
        hidden = providers.gradleProperty("hidden").map { it.toBoolean() }.orElse(false)
    }
}

// Compose-based settings panels are not Swing-indexable; disable searchable options build
tasks.buildSearchableOptions {
    enabled = false
}

tasks.runIde {
    jvmArgs = buildList {
        add("-Dopencode.platform.version=${providers.gradleProperty("platformVersion").get()}")
        add("-Didea.auto.reload.plugins=true")
        // Skiko debug logging — only enabled with -PskikoDebug=true
        if (providers.gradleProperty("skikoDebug").getOrElse("false").toBoolean()) {
            add("-Dskiko.log=debug")
        }
        // Skiko renderer: do NOT set skiko.renderApi=SOFTWARE.
        // SOFTWARE mode forces Skiko to render to a BufferedImage, which Swing
        // then blits to screen via GDIBlitLoops.nativeBlit() → GDI BitBlt → DWM
        // composition. That is the exact code path that hangs the EDT for 60+
        // seconds (DWM composition deadlock). Without this flag, Skiko defaults
        // to Direct3D on Windows, rendering directly to a GPU swap chain and
        // presenting via DXGI Present() — GDI BitBlt is never called.
        // The D3D exit-hang (GPU context teardown) is handled by async
        // ComposePanel disposal on a daemon thread (disposeActiveComposePanelAsync).
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get()))
    }
}

tasks.test {
    useJUnitPlatform()
}
