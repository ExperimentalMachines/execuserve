/*
 * The app's core, minus the platform: what the settings are and what they mean, starting
 * and stopping the engine and the listener as one, and what each state of the server is
 * called (as enums; each platform supplies the words and the colours). Android and iOS
 * both build their console on this.
 */
plugins {
    id("execuserve.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:server"))
        }
        jvmTest.dependencies {
            implementation(project(":jvm:testing"))
        }
    }
}
