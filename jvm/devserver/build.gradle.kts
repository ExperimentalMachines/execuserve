/*
 * The server, on a laptop, over a scripted runtime: the whole HTTP stack, the scheduler and
 * the templates, with no model. For testing clients (the OpenAI SDKs, Open WebUI, agents)
 * against ExecuServe's exact wire behaviour before a phone is involved.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("org.experimentalmachines.execuserve.devserver.MainKt")
    applicationName = "execuserve-dev"
}

dependencies {
    implementation(project(":shared:server"))
    implementation(project(":shared:catalog"))
    implementation(project(":jvm:testing"))
}
