import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

object BuildConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36

    /**
     * 31, the same floor as OpenWeights: the ExecuTorch AAR and the foreground-service rules
     * this app is written against both assume Android 12 or later.
     */
    const val MIN_SDK = 31

    /** The ExecuTorch AAR also carries x86_64, which only the emulator could use. */
    val ABIS = setOf("arm64-v8a")

    const val NDK_VERSION = "29.0.14206865"

    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
    val JVM_TARGET: JvmTarget = JvmTarget.JVM_17

    /** `:shared:engine` becomes `org.experimentalmachines.execuserve.engine`. */
    fun namespaceFor(project: Project): String =
        "org.experimentalmachines.execuserve." + project.path.substringAfterLast(':').replace('-', '.')
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
