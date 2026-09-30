import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.namespace = BuildConfig.namespaceFor(this)
    extension.compileSdk = BuildConfig.COMPILE_SDK
    extension.defaultConfig.minSdk = BuildConfig.MIN_SDK
    extension.defaultConfig.ndk.abiFilters += BuildConfig.ABIS
    // Without an NDK the strip task copies ExecuTorch's libraries through unstripped.
    extension.ndkVersion = BuildConfig.NDK_VERSION
    extension.compileOptions.sourceCompatibility = BuildConfig.JAVA_VERSION
    extension.compileOptions.targetCompatibility = BuildConfig.JAVA_VERSION
    extension.testOptions.unitTests.isReturnDefaultValues = true

    extensions.getByType<KotlinAndroidProjectExtension>().compilerOptions {
        jvmTarget.set(BuildConfig.JVM_TARGET)
        freeCompilerArgs.addAll("-Xconsistent-data-class-copy-visibility")
    }
}
