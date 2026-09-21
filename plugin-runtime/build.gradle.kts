plugins {
    kotlin("jvm")
    `java-library`
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xopt-in=com.lagradost.cloudstream3.Prerelease",
            "-Xopt-in=com.lagradost.cloudstream3.InternalAPI",
            "-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":android-shims"))
    implementation(project(":library"))

    api(libs.newpipeextractor)
    implementation(libs.anime.db)
    implementation(libs.annotation)
    implementation(libs.nicehttp)
    implementation(libs.ksoup)
    implementation(libs.dex.tools)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.conscrypt.openjdk)
    implementation(libs.kotlinx.io.core)
    compileOnly("org.jetbrains.compose.runtime:runtime:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val generateGitHash = tasks.register("generateGitHash") {
    val gitDir = rootProject.layout.projectDirectory.dir(".git")
    val outputFile = layout.buildDirectory.file("generated/resources/git-hash.txt")
    outputs.file(outputFile)

    val headFile = gitDir.file("HEAD")
    if (headFile.asFile.exists()) {
        inputs.file(headFile)
    }

    doLast {
        val hash = try {
            val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(rootProject.rootDir)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && text.isNotEmpty()) {
                text
            } else {
                val head = headFile.asFile
                if (head.exists()) {
                    val headContent = head.readText().trim()
                    if (headContent.startsWith("ref:")) {
                        val refPath = headContent.substring(5).trim()
                        val commitFile = File(head.parentFile, refPath)
                        if (commitFile.exists()) commitFile.readText().trim().take(7) else ""
                    } else headContent.take(7)
                } else ""
            }
        } catch (_: Throwable) {
            ""
        }

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(hash)
        }

        val buildTimeFile = layout.buildDirectory.file("generated/resources/build-time.txt").get().asFile
        buildTimeFile.parentFile.mkdirs()
        buildTimeFile.writeText(System.currentTimeMillis().toString())
    }
}

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/resources"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateGitHash)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
