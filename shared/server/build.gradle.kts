/*
 * The HTTP face of the engine: OpenAI's routes over Ktor's CIO engine, which runs on the JVM
 * (Android) and on Kotlin/Native (iOS), so this module ships to both unchanged.
 */
plugins {
    id("execuserve.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:api"))
            api(project(":shared:engine"))
            api(libs.ktor.server.core)
            api(libs.ktor.server.cio)
            implementation(libs.ktor.server.cors)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(project(":jvm:testing"))
        }
    }
}
