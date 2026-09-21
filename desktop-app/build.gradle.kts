import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-Xopt-in=com.lagradost.cloudstream3.Prerelease",
            "-Xopt-in=com.lagradost.cloudstream3.InternalAPI",
            "-Xopt-in=kotlin.uuid.ExperimentalUuidApi",
            "-Xopt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    // Core Architecture Subprojects
    implementation(project(":library"))
    implementation(project(":common"))
    implementation(project(":android-shims"))
    implementation(project(":plugin-runtime"))
    implementation(project(":player-mpv"))
    implementation("org.openani.mediamp:mediamp-all-desktop:0.5.0")
    runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime-linux-x64:0.5.0")
    runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime-windows-x64:0.5.0")
    implementation(libs.nicehttp)
    implementation(libs.okhttp)

    // Compose Desktop UI & Multiplatform Native Runtimes (Linux & Windows x64)
    implementation(compose.desktop.linux_x64)
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.ui)
    implementation(compose.foundation)

    // Coil 3 Modern Image Loading (Memory & Disk Cache)
    implementation(libs.coil) // implementation(libs.coil3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp) // implementation(libs.coil3.network.okhttp)

    // Serialization & JSON
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines (Swing dispatcher provides Dispatchers.Main on desktop JVM)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    // Logging
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // NO PLAYWRIGHT, NO JAVAFX
}

compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream3.desktop.MainKt"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.AppImage,
                TargetFormat.Deb,
                TargetFormat.Msi,
                TargetFormat.Exe,
            )
            packageName = "cloudstream-desktop"
            packageVersion = "1.0.0"
            description = "CloudStream Desktop Client"
            vendor = "MematiBas42"

            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.naming",
                "java.net.http",
                "java.scripting",
                "java.sql",
                "java.xml",
                "java.management",
                "jdk.unsupported",
                "jdk.unsupported.desktop",
                "jdk.crypto.cryptoki",
                "jdk.crypto.ec",
                "jdk.zipfs",
                "jdk.charsets"
            )

            linux {
                iconFile.set(project.file("src/main/resources/logo_ui.png"))
            }

            windows {
                val winIcon = project.file("src/main/resources/logo_ui.ico")
                iconFile.set(if (winIcon.exists()) winIcon else project.file("src/main/resources/logo_ui.png"))
                menu = true
                menuGroup = "CloudStream"
                upgradeUuid = "b845d44d-17e9-4e78-9e63-8a30f7813a45"
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}

tasks.matching { it.name.contains("UberJar") }.configureEach {
    doLast {
        val jarDir = project.layout.buildDirectory.dir("compose/jars").get().asFile
        if (jarDir.exists()) {
            jarDir.walkTopDown().filter { it.extension.equals("jar", ignoreCase = true) }.forEach { jarFile ->
                val uri = URI.create("jar:" + jarFile.toURI().toASCIIString())
                try {
                    FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { fs ->
                        val metaInf = fs.getPath("/META-INF")
                        if (Files.exists(metaInf)) {
                            val targets = mutableListOf<Path>()
                            Files.list(metaInf).use { stream ->
                                stream.forEach { p ->
                                    val n = p.fileName?.toString()?.uppercase() ?: ""
                                    if (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA")) {
                                        targets.add(p)
                                    }
                                }
                            }
                            targets.forEach { Files.deleteIfExists(it) }
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore already uncompressed/cleaned jar
                }
            }
        }
    }
}
