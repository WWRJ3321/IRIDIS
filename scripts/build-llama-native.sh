#!/usr/bin/env sh
# Builds libiris_llama.so (llama.cpp + JNI wrapper, arm64-v8a) outside Gradle and drops it into
# prebuilt-native/arm64-v8a/. Same flags as the Gradle CMake build (c++_static, LTO, KleidiAI, OpenCL).
# Usage: NDK=/path/to/android-ndk sh scripts/build-llama-native.sh   (llama.cpp from scripts/fetch-deps.sh)
# 2.3.6: Vulkan (Mali / Immortalis / Xclipse) when VK_HEADERS (Khronos Vulkan-Headers checkout, has
# include/vulkan/vulkan.hpp), SPIRV_HEADERS (SPIRV-Headers install prefix) and GLSLC (host glslc) are
# set; .github/workflows/native.yml does this. Without them the library is OpenCL-only as before.
set -eu
cd "$(dirname "$0")/.."
NDK="${NDK:?set NDK=/path/to/android-ndk}"
B=build-native; rm -rf "$B"
VK_ARGS=""
if [ -n "${VK_HEADERS:-}" ]; then
  SYSROOT="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/sysroot | head -1)"
  VK_ARGS="-DIRIS_VULKAN=ON -DVulkan_INCLUDE_DIR=$VK_HEADERS/include \
    -DVulkan_LIBRARY=$SYSROOT/usr/lib/aarch64-linux-android/31/libvulkan.so \
    -DVulkan_GLSLC_EXECUTABLE=${GLSLC:?set GLSLC=/path/to/glslc} \
    -DSPIRV-Headers_DIR=${SPIRV_HEADERS:?set SPIRV_HEADERS}/share/cmake/SPIRV-Headers \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH"
fi
cmake -G Ninja -S app/src/main/cpp -B "$B" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DIRIS_KLEIDIAI=ON -DIRIS_LTO=ON -DIRIS_IMAGEGEN=OFF -DIRIS_MNN=OFF \
  -DLLAMA_DIR="$PWD/third_party/llama.cpp" $VK_ARGS
ninja -C "$B" iris_llama
mkdir -p prebuilt-native/arm64-v8a
"$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)/llvm-strip" --strip-unneeded -o prebuilt-native/arm64-v8a/libiris_llama.so "$B/libiris_llama.so"
echo "OK: prebuilt-native/arm64-v8a/libiris_llama.so"
