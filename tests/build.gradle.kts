plugins {
    kotlin("jvm")
    `java-library`
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xopt-in=com.lagradost.cloudstream3.Prerelease",
            "-Xopt-in=com.lagradost.cloudstream3.InternalAPI",
            "-Xopt-in=kotlin.uuid.ExperimentalUuidApi",
            "-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    testImplementation(project(":common"))
    testImplementation(project(":android-shims"))
    testImplementation(project(":library"))
    testImplementation(project(":plugin-runtime"))
    testImplementation(project(":player-mpv"))
    testImplementation(project(":desktop-app"))
    testImplementation(compose.desktop.currentOs)
    testImplementation(compose.material3)
    testImplementation(compose.ui)
    testImplementation(compose.foundation)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.io.core)
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/*RemediationTest.kt")
}

