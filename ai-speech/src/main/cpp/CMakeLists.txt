cmake_minimum_required(VERSION 3.22.1)
project(finder_asr C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build only what the ASR bridge needs from llama.cpp: the core libs plus the multimodal (mtmd)
# audio path that implements Qwen3-ASR. Tools/examples/tests/server stay off to keep build time and
# APK size down. LLAMA_BUILD_MTMD adds tools/mtmd on its own when LLAMA_BUILD_TOOLS is OFF.
set(LLAMA_BUILD_MTMD      ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)   # not shipped in the NDK sysroot

# mtmd's video path shells out to an ffmpeg binary via posix_spawn, which bionic does not provide at
# our minSdk (and posix_spawn_file_actions_addchdir_np does not exist on Android at all). We only feed
# it PCM audio, so video support is off — this is the only thing that blocked the Android build.
set(MTMD_VIDEO            OFF CACHE BOOL "" FORCE)

# Optional GPU compute on the phone's GPU (Xclipse 940 on Exynos 2400). Off by default because
# Vulkan shader compilation lengthens the build and driver support varies; ggml keeps layers on the
# CPU automatically when no usable Vulkan device is found, so enabling it is never fatal.
if (FINDER_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    # ggml's Vulkan backend compiles its compute shaders at build time, so it needs a host glslc and
    # the SPIRV-Headers CMake package. Neither ships in the NDK, and neither is discoverable when
    # cross-compiling, so both are passed in explicitly.
    if (FINDER_GLSLC)
        set(Vulkan_GLSLC_EXECUTABLE ${FINDER_GLSLC} CACHE FILEPATH "" FORCE)
    endif()
    if (FINDER_SPIRV_HEADERS)
        set(SPIRV-Headers_DIR ${FINDER_SPIRV_HEADERS} CACHE PATH "" FORCE)
    endif()
    # The NDK ships Vulkan's C headers but not the C++ ones (vulkan.hpp) that ggml-vulkan includes,
    # so they are taken from the host system — but ONLY those.
    #
    # Putting the host's /usr/include on the search path directly is what the obvious version of this
    # does, and it breaks the build in a thoroughly confusing way: glibc's headers then shadow the
    # NDK's bionic ones and CMake's very first compiler probe fails with "pthread.h - not found".
    # Instead, a directory containing a single `vulkan` symlink is synthesized, so `<vulkan/vulkan.hpp>`
    # resolves and nothing else from the host leaks into a cross-compile.
    #
    # The flag is needed in addition to include_directories() because ggml creates its backend targets
    # through a helper that assigns INCLUDE_DIRECTORIES outright, discarding anything inherited.
    if (FINDER_VULKAN_INCLUDE)
        set(FINDER_VK_SHIM ${CMAKE_BINARY_DIR}/vulkan-headers)
        file(MAKE_DIRECTORY ${FINDER_VK_SHIM})
        # All three matter, and each was discovered by the build failing one header deeper than the
        # last: vulkan_core.h includes <vk_video/...> for the video-codec structs, and ggml-vulkan
        # itself includes <spirv/unified1/spirv.hpp> to introspect compiled shaders.
        foreach (dir vulkan vk_video spirv)
            if (NOT EXISTS ${FINDER_VK_SHIM}/${dir} AND EXISTS ${FINDER_VULKAN_INCLUDE}/${dir})
                file(CREATE_LINK ${FINDER_VULKAN_INCLUDE}/${dir} ${FINDER_VK_SHIM}/${dir} SYMBOLIC)
            endif()
        endforeach()
        include_directories(${FINDER_VK_SHIM})
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -isystem ${FINDER_VK_SHIM}")
    endif()
    # llama.cpp builds vulkan-shaders-gen for the HOST when cross-compiling, but only if given a host
    # toolchain file. Omitting it produced an arm64 generator that could not run on the build machine.
    if (FINDER_HOST_TOOLCHAIN)
        set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN ${FINDER_HOST_TOOLCHAIN} CACHE FILEPATH "" FORCE)
    endif()
endif()

add_subdirectory(llama.cpp)

# Re-apply the Vulkan C++ headers directly to the target.
#
# The directory-scope include_directories() above does not survive: ggml creates its backend targets
# through a helper that sets INCLUDE_DIRECTORIES explicitly, which replaces the inherited list rather
# than extending it. The symptom is a compile of ggml-vulkan.cpp with no -I for Vulkan at all and a
# "'vulkan/vulkan.hpp' file not found" error, despite the path being configured correctly.
# SYSTEM suppresses warnings from vulkan.hpp, which ggml builds with -Wpedantic -Wextra.
if (FINDER_VULKAN AND FINDER_VULKAN_INCLUDE AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan SYSTEM PRIVATE ${FINDER_VULKAN_INCLUDE})
endif()

# whisper.cpp as a second speech engine, sharing llama.cpp's ggml.
#
# Order matters: whisper.cpp wraps its own ggml in `if (NOT TARGET ggml)`, so adding it *after*
# llama.cpp makes it reuse the ggml (and Vulkan backend) already configured above instead of defining
# duplicate targets, which is what a naive add_subdirectory would do.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
add_subdirectory(whisper.cpp)

add_library(finder_asr SHARED finder_asr.cpp finder_whisper.cpp)

target_include_directories(finder_asr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/tools/mtmd
)

target_link_libraries(finder_asr PRIVATE mtmd llama whisper ggml android log)

# Let the JNI layer query the Vulkan driver's own capability list, so "does this GPU expose
# cooperative matrix?" is answered by the driver rather than guessed from throughput.
if (FINDER_VULKAN)
    target_compile_definitions(finder_asr PRIVATE FINDER_HAS_VULKAN)
    target_link_libraries(finder_asr PRIVATE vulkan)
endif()
