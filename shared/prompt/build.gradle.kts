/*
 * Everything about turning an OpenAI message list into the exact bytes a compiled model was
 * trained on, and turning its output back into text and tool calls.
 *
 * Vendored from OpenWeights' :core:common (Apache-2.0, same authors), where every template
 * is tested byte-equal against its family's Jinja. A .pte carries no chat template, so these
 * renderers are the only thing standing between a request and a model that answers a little
 * worse for reasons nobody can see.
 */
plugins {
    id("execuserve.kmp.library")
}

dependencies {
    // Tool-call arguments are JSON documents; reading them as trees beats another hand parser.
    "commonMainImplementation"(libs.kotlinx.serialization.json)
}

kotlin {
    sourceSets {
        // One file needs java.util.Calendar and has an NSCalendar twin on iOS.
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        androidMain { dependsOn(jvmAndAndroidMain) }
        jvmMain { dependsOn(jvmAndAndroidMain) }

        // The fixtures came with JUnit and Truth, and are about text, not platforms.
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.truth)
        }
    }
}
