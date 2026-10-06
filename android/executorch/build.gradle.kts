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
    // XNNPACK (CPU), Vulkan (GPU) and QNN (Qualcomm NPU) in one runtime, built by tools/executorch.
    implementation(libs.executorch.android)
    // The HTP drivers the QNN delegate loads at run time.
    implementation(libs.qnn.runtime)
    // VulkanSupport.usableState, which the Models screen collects.
    api(libs.kotlinx.coroutines.core)
}
