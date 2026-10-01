/*
 * The Android app: a foreground service that keeps the server up, and a Compose console
 * to see and steer it. Everything the server decides lives in :shared; this module is how
 * Android lets it run.
 */
plugins {
    id("execuserve.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        applicationId = "org.experimentalmachines.execuserve"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key until a release key exists, so the build is installable.
            signingConfig = signingConfigs.getByName("debug")
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
