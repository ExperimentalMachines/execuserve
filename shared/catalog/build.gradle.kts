/*
 * What is installed and what could be: model folders on disk, their manifests, and the
 * Hugging Face catalog they come from. File access goes through [ModelFiles]-style
 * interfaces so the scanning rules are shared; the JVM and Android share one implementation.
 */
plugins {
    id("execuserve.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        androidMain { dependsOn(jvmAndAndroidMain) }
        jvmMain { dependsOn(jvmAndAndroidMain) }

        commonMain.dependencies {
            api(project(":shared:engine"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
