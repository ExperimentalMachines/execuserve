/*
 * The Android app: a foreground service that keeps the server up, and a Compose console
 * to see and steer it. Everything the server decides lives in :shared; this module is how
 * Android lets it run.
 */
import java.util.Properties

plugins {
    id("execuserve.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The lowest version code this repository may build from: a ratchet, raised by hand only if
 * a rewritten history (a squash, a rebase) ever takes the commit count below a code already
 * uploaded to Play. Declared before [gitCommitCount], which reads it.
 */
val versionCodeFloor = 1

/**
 * The number of commits on this branch, used as the version code (the OpenWeights scheme).
 *
 * Play's one rule is that each upload's code is higher than every earlier one, forever. A
 * typed code is a promise to remember; the commit count rises on its own, needs no service
 * or secret, and is the same here as in CI. Releases are cut from `main`, since a branch with
 * fewer commits counts lower. A shallow clone would count wrong without a word, so it stops
 * the build instead (CI checks out the full history for this reason).
 */
val gitCommitCount: Int = run {
    val git = { args: List<String> ->
        runCatching {
            providers.exec {
                commandLine(args)
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
    if (git(listOf("git", "rev-parse", "--is-shallow-repository")) == "true") {
        throw GradleException(
            "This is a shallow clone, so the commit count, and the version code built from it, " +
                "would be wrong. Fetch the full history (actions/checkout with fetch-depth: 0).",
        )
    }
    val counted = git(listOf("git", "rev-list", "--count", "HEAD"))?.toIntOrNull()
    // Only a source archive, with no .git at all, may fall back; nothing built that way is
    // publishable. A checkout whose count cannot be read is a broken build.
    if (counted == null && rootProject.file(".git").exists()) {
        throw GradleException("There is a .git here but `git rev-list --count HEAD` failed; check that git runs.")
    }
    val code = counted?.takeIf { it > 0 } ?: 1
    if (counted != null && code < versionCodeFloor) {
        throw GradleException(
            "The commit count is $code, below the floor of $versionCodeFloor, so the version code would go " +
                "backwards and Play would refuse the bundle. Raise versionCodeFloor above the highest code uploaded.",
        )
    }
    code
}

/*
 * Release signing: the upload key, read from keystore.properties (git-ignored) or the
 * environment, never from the repository. Play App Signing holds the key users verify.
 *
 *   keystore.properties   storeFile, storePassword, keyAlias, keyPassword
 *   environment           EXECUSERVE_KEYSTORE, _KEYSTORE_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)

val uploadKeystore: String? = secret("storeFile", "EXECUSERVE_KEYSTORE")

android {
    defaultConfig {
        applicationId = "org.experimentalmachines.execuserve"
        // Counted, not typed: see gitCommitCount. The name is editorial and stays typed.
        versionCode = gitCommitCount
        versionName = "0.1.0"
    }
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                // Resolved against the root, where keystore.properties lives.
                storeFile = rootProject.file(uploadKeystore)
                storePassword = secret("storePassword", "EXECUSERVE_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "EXECUSERVE_KEY_ALIAS")
                keyPassword = secret("keyPassword", "EXECUSERVE_KEY_PASSWORD")
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    lint {
        // Clean today, so any new finding fails the build; deliberate exceptions live in
        // lint.xml with their reason.
        warningsAsErrors = true
        abortOnError = true
        // A newer AndroidX or AGP is news, not a defect; dependency bumps are their own change.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The upload key when one is configured. Without it, the debug key, so a release
            // build still installs over the one already on a test phone (a different key would
            // force an uninstall and lose its models); bundleRelease refuses that case below.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(libs.zxing.core)
    implementation(project(":shared:host"))
    implementation(project(":shared:catalog"))
    implementation(project(":android:executorch"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

// The bundle is what goes to Play, and Play rejects one signed with the debug key; better to
// stop before building it than to find out at upload.
val hasUploadKey = uploadKeystore != null
tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!hasUploadKey) {
            throw GradleException(
                "bundleRelease needs the upload key: add keystore.properties (storeFile, storePassword, keyAlias, " +
                    "keyPassword) or set EXECUSERVE_KEYSTORE and its three companions. See docs/RELEASING.md.",
            )
        }
    }
}
