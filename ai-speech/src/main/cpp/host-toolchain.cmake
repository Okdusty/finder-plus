# Toolchain for building ggml's `vulkan-shaders-gen` for the BUILD machine.
#
# That tool is executed *during* the build to compile GLSL compute shaders into SPIR-V. Under the
# Android NDK's toolchain it would be cross-compiled for arm64, producing a binary the host cannot
# execute — which surfaces as the build hanging at shader generation rather than as a clean error.
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

set(CMAKE_C_COMPILER cc)
set(CMAKE_CXX_COMPILER c++)

# Drop the Android flags inherited from the parent configure; they are meaningless for a host binary
# and some (`--target=aarch64-linux-android`) actively break it.
set(CMAKE_C_FLAGS "")
set(CMAKE_CXX_FLAGS "")
set(CMAKE_EXE_LINKER_FLAGS "")
set(CMAKE_SYSROOT "")

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
