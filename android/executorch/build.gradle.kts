/*
 * The ExecuTorch runtime behind the engine's SPI, through `org.pytorch:executorch-android`.
 * The only module that touches the native library; iOS gets its own twin over the Apple
 * frameworks, and a Vulkan or NPU build is another implementation of the same interfaces.
 */
plugins {
    id("execuserve.android.library")
}

android {
    buildFeatures { buildConfig = true }
    // One version, from the catalog: the console shows it, and catalog exports are checked
    // against it.
    defaultConfig { buildConfigField("String", "EXECUTORCH_VERSION", "\"${libs.versions.executorch.get()}\"") }
}

dependencies {
    api(project(":shared:engine"))
    // The XNNPACK build, which every export in the experimentalmachines catalog targets.
    implementation(libs.executorch.android)
}
