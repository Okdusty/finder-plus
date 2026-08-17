plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ai.rightone.finderplus"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "ai.dusty.finderplus"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-beta"
        // Target device (Exynos 2400) is arm64-only; shipping x86/x86_64/armeabi-v7a copies of the
        // ML Kit, ONNX and llama.cpp .so files was pure dead weight in the APK.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        // Local release keystore. Credentials come from gradle properties (finderplus.store.password /
        // finderplus.key.password) so nothing secret lives in this file; the keystore itself is a
        // generated self-signed key for sideload deployment - replace for a store release.
        create("release") {
            val store = rootProject.file("keys/finderplus-release.jks")
            if (store.exists()) {
                storeFile = store
                storePassword = (project.findProperty("finderplus.store.password") as String?) ?: ""
                keyAlias = "finderplus"
                keyPassword = (project.findProperty("finderplus.key.password") as String?) ?: ""
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-db"))
    implementation(project(":core-media"))
    implementation(project(":engine-index"))
    implementation(project(":engine-search"))
    implementation(project(":ai-vision"))
    implementation(project(":ai-speech"))
    implementation(project(":ai-text"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.work.runtime)

    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    // UI layer (design-only this phase). Deps declared so the wireframes can be implemented next.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}
