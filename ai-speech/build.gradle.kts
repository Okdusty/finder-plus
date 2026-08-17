plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.rightone.finderplus.speech"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 26
        // Exynos 2400 (SM-S926B) is arm64-v8a only — one ABI keeps the native build and APK lean.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                // Release-optimized: ASR throughput is the whole point, and -O0 would be unusable.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    // Flip to ON to compile the Vulkan GPU backend (adds shader-compile time).
                    "-DFINDER_VULKAN=${project.findProperty("finderVulkan") ?: "OFF"}",
                    "-DFINDER_GLSLC=${project.findProperty("finderGlslc") ?: ""}",
                    "-DFINDER_SPIRV_HEADERS=${project.findProperty("finderSpirvHeaders") ?: ""}",
                    "-DFINDER_VULKAN_INCLUDE=${project.findProperty("finderVulkanInclude") ?: ""}",
                    "-DFINDER_HOST_TOOLCHAIN=${project.findProperty("finderHostToolchain") ?: ""}",
                    // ggml-vulkan calls vkGetPhysicalDeviceFeatures2, a Vulkan 1.1 entry point that
                    // Android's stub libvulkan only exports from API 28. Linking against the API-26
                    // stub fails at link time, so the NATIVE build targets 28 while the app keeps
                    // minSdk 26 (the Vulkan backend is optional and falls back to CPU).
                    "-DANDROID_PLATFORM=android-${if (project.findProperty("finderVulkan") == "ON") "28" else "26"}",
                )
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core-model"))
    implementation(project(":core-media"))
    // Silero VAD runs on ONNX Runtime; the AAR is already in the APK via :ai-vision.
    implementation(libs.onnxruntime.android)
    api(project(":ai-vision"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
