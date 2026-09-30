import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A module that describes what the server does rather than how a platform lets it.
 *
 * Four targets, each with a job. `android` is what ships. `iosArm64` and
 * `iosSimulatorArm64` are where this is heading, compiled on every build so a platform call
 * in `commonMain` fails here and not in a year. `jvm` ships nothing and earns its place
 * anyway: it runs the tests on any machine in seconds, and it is what `:jvm:devserver`
 * runs the real HTTP stack on.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        extensions.configure<KotlinMultiplatformExtension> {
            (this as ExtensionAware).extensions.getByType<KotlinMultiplatformAndroidLibraryExtension>().apply {
                namespace = BuildConfig.namespaceFor(target)
                compileSdk = BuildConfig.COMPILE_SDK
                minSdk = BuildConfig.MIN_SDK
            }
            jvm {
                compilerOptions { jvmTarget.set(BuildConfig.JVM_TARGET) }
            }
            iosArm64()
            iosSimulatorArm64()

            applyDefaultHierarchyTemplate()

            compilerOptions {
                freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xconsistent-data-class-copy-visibility")
            }

            sourceSets.getByName("commonMain").dependencies {
                implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
                implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }

        tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
            // A hung scheduler test is worse than a failing one.
            timeout.set(java.time.Duration.ofMinutes(5))
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
