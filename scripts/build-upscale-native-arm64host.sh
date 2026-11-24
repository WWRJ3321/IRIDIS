#!/usr/bin/env sh
# 3.0.0 Beta5: libiris_upscale.so (生图后超分, CPU ESRGAN) on an aarch64 Linux host. Same toolchain as build-sd-native-arm64host.sh.
set -eu
cd "$(dirname "$0")/.."
export NDK="${NDK:?set NDK}"
SD=third_party/stable-diffusion.cpp   # leejet/stable-diffusion.cpp 3e92bf9 (+ ggml submodule)
B=build-upscale-arm64host
cmake -G Ninja -S app/src/main/cpp/upscale -B "$B" -DCMAKE_TOOLCHAIN_FILE="$PWD/scripts/android-arm64host.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm+fp16 -DSD_DIR="$PWD/$SD" -DGGML_CCACHE=OFF
ninja -C "$B" -j2 iris_upscale
mkdir -p prebuilt-native/arm64-v8a
llvm-strip-19 --strip-unneeded -o prebuilt-native/arm64-v8a/libiris_upscale.so "$B/libiris_upscale.so"
echo "OK: prebuilt-native/arm64-v8a/libiris_upscale.so"
