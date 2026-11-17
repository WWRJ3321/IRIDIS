#!/usr/bin/env sh
# 2.7.0: libiris_sd.so on an aarch64 Linux host (NDK clang is x86_64-only). Same sources/flags as build-sd-native.sh.
# Needs (Debian): clang-19 lld-19 llvm-19 glslc spirv-headers libvulkan-dev cmake ninja-build; the NDK's
# libclang_rt.builtins-aarch64-android.a and aarch64/libunwind.a copied into clang-19's resource dir (lib/linux/).
# Vulkan headers are copied to $VKINC/include so /usr/include never enters the cross build.
set -eu
cd "$(dirname "$0")/.."
export NDK="${NDK:?set NDK}"
VKINC="${VKINC:?set VKINC (dir with include/vulkan, include/vk_video)}"
SD=third_party/stable-diffusion.cpp
B=build-sd-arm64host
cmake -G Ninja -S app/src/main/cpp/sdcpp -B "$B" -DCMAKE_TOOLCHAIN_FILE="$PWD/scripts/android-arm64host.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm+fp16 -DSD_DIR="$PWD/$SD" \
  -DSD_VULKAN=ON -DVulkan_INCLUDE_DIR="$VKINC/include" \
  -DVulkan_LIBRARY="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/31/libvulkan.so" \
  -DVulkan_GLSLC_EXECUTABLE="$(command -v glslc)" -DSPIRV-Headers_DIR=/usr/share/cmake/SPIRV-Headers -DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN="$PWD/scripts/host-native.cmake" -DGGML_CCACHE=OFF
ninja -C "$B" -j2 iris_sd
mkdir -p prebuilt-native/arm64-v8a
llvm-strip-19 --strip-unneeded -o prebuilt-native/arm64-v8a/libiris_sd.so "$B/libiris_sd.so"
echo "OK: prebuilt-native/arm64-v8a/libiris_sd.so ($(git -C "$SD" rev-parse --short HEAD))"
