plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("verify") {
    group = "verification"
    description = "Every host test tier plus the Android and iOS compilations."
    val wanted = listOf(
        Regex("jvmTest"),
        Regex("testDebugUnitTest"),
        Regex("assembleDebug"),
        // Proves commonMain has no JVM in it. Linking and running needs a simulator.
        Regex("compileKotlinIosSimulatorArm64"),
    )
    dependsOn(subprojects.map { p -> p.tasks.matching { t -> wanted.any { it.matches(t.name) } } })
}
