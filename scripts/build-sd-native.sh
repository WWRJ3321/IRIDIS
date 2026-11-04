#!/usr/bin/env sh
# IRIDIS 2.4.0: builds libiris_sd.so (upstream stable-diffusion.cpp + app/src/main/cpp/sdcpp/sd_jni.cpp)
# into prebuilt-native/arm64-v8a/. CPU (KleidiAI-free, NEON/i8mm/dotprod via ggml-cpu) + Vulkan when
# VK_HEADERS / SPIRV_HEADERS / GLSLC are set (same variables as build-llama-native.sh).
# Usage: NDK=/path/to/android-ndk sh scripts/build-sd-native.sh
set -eu
cd "$(dirname "$0")/.."
NDK="${NDK:?set NDK=/path/to/android-ndk}"
SD_COMMIT="3e92bf990ad70566212ee5eb27ad7570fb7ed2af"   # leejet/stable-diffusion.cpp master 2026-11-04
SD=third_party/stable-diffusion.cpp
if [ "$(git -C "$SD" rev-parse HEAD 2>/dev/null || true)" != "$SD_COMMIT" ]; then
  rm -rf "$SD"; mkdir -p "$SD"
  git -C "$SD" init -q
  git -C "$SD" remote add origin https://github.com/leejet/stable-diffusion.cpp
  git -C "$SD" fetch -q --depth 1 origin "$SD_COMMIT"
  git -C "$SD" checkout -q FETCH_HEAD
  git -C "$SD" submodule update -q --init --depth 1 ggml
fi
B=build-sd; rm -rf "$B"
VK_ARGS="-DSD_VULKAN=OFF"
if [ -n "${VK_HEADERS:-}" ]; then
  SYSROOT="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/sysroot | head -1)"
  VK_ARGS="-DSD_VULKAN=ON -DVulkan_INCLUDE_DIR=$VK_HEADERS/include \
    -DVulkan_LIBRARY=$SYSROOT/usr/lib/aarch64-linux-android/31/libvulkan.so \
    -DVulkan_GLSLC_EXECUTABLE=${GLSLC:?set GLSLC} \
    -DSPIRV-Headers_DIR=${SPIRV_HEADERS:?set SPIRV_HEADERS}/share/cmake/SPIRV-Headers \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH"
fi
cmake -G Ninja -S app/src/main/cpp/sdcpp -B "$B" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm+fp16 \
  -DSD_DIR="$PWD/$SD" $VK_ARGS
ninja -C "$B" iris_sd
mkdir -p prebuilt-native/arm64-v8a
"$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)/llvm-strip" --strip-unneeded -o prebuilt-native/arm64-v8a/libiris_sd.so "$B/libiris_sd.so"
echo "OK: prebuilt-native/arm64-v8a/libiris_sd.so ($(git -C "$SD" rev-parse --short HEAD))"
