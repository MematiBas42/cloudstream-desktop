plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xopt-in=com.lagradost.cloudstream3.Prerelease",
            "-Xopt-in=com.lagradost.cloudstream3.InternalAPI",
            "-Xopt-in=kotlin.uuid.ExperimentalUuidApi",
        )
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":android-shims"))
    implementation(project(":library"))
    implementation(project(":plugin-runtime"))
    implementation(libs.annotation)
    implementation(libs.nicehttp)
    implementation(libs.okhttp)
    // implementation(libs.coroutines.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
}
