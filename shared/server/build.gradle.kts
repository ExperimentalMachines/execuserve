/*
 * The HTTP face of the engine: OpenAI's routes over Ktor's CIO engine, which runs on the JVM
 * (Android) and on Kotlin/Native (iOS), so this module ships to both unchanged.
 */
plugins {
    id("execuserve.kmp.library")
}

// Embed the same small, dependency-free client on JVM, Android and Kotlin/Native.
val webDirectory = layout.projectDirectory.dir("src/commonMain/web")
val generatedWeb = layout.buildDirectory.dir("generated/web/kotlin")
val generateWebAssets = tasks.register("generateWebAssets") {
    inputs.dir(webDirectory)
    outputs.dir(generatedWeb)
    doLast {
        fun literal(text: String): String = buildString {
            append('"')
            text.forEach { c ->
                append(when (c) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '$' -> "\\$"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> c.toString()
                })
            }
            append('"')
        }
        val target = generatedWeb.get().file("org/experimentalmachines/execuserve/server/WebAssets.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(buildString {
            appendLine("package org.experimentalmachines.execuserve.server")
            appendLine("internal object WebAssets {")
            mapOf("html" to "index.html", "css" to "chat.css", "js" to "chat.js", "mark" to "mark.svg").forEach { (name, file) ->
                appendLine("    val $name: String = ${literal(webDirectory.file(file).asFile.readText())}")
            }
            appendLine("}")
        })
    }
}

kotlin {
    sourceSets {
        commonMain { kotlin.srcDir(files(generatedWeb).builtBy(generateWebAssets)) }
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
