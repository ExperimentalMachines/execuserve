plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.get())
        android.set(true)
        filter {
            // Generated sources (the web chat embedded as Kotlin strings) are not ours to style.
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        buildUponDefaultConfig = true
        parallel = true
        // Detekt reads src/main and src/test by default, which a multiplatform module does
        // not have; without this list a KMP module goes NO-SOURCE without a word.
        source.setFrom(
            files(
                "src/main/kotlin",
                "src/test/kotlin",
                "src/commonMain/kotlin",
                "src/commonTest/kotlin",
                "src/jvmMain/kotlin",
                "src/jvmTest/kotlin",
                "src/androidMain/kotlin",
                "src/iosMain/kotlin",
            ).filter { it.exists() },
        )
    }

    // A warning today is a bug report nobody reads tomorrow: every compilation, on every
    // target, fails on one.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Lint and static analysis, every host test tier, and the Android and iOS compilations."
    val wanted = listOf(
        Regex("ktlintCheck"),
        Regex("detekt"),
        Regex("lintDebug"),
        Regex("jvmTest"),
        Regex("testDebugUnitTest"),
        Regex("assembleDebug"),
        // Proves commonMain has no JVM in it. Linking and running needs a simulator.
        Regex("compileKotlinIosSimulatorArm64"),
    )
    dependsOn(subprojects.map { p -> p.tasks.matching { t -> wanted.any { it.matches(t.name) } } })
}
