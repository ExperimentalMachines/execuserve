/*
 * The OpenAI wire format, and ExecuServe's own status format. Types and JSON only: nothing
 * here knows what a model or a phone is, so a client library could depend on it too.
 */
plugins {
    id("execuserve.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}
