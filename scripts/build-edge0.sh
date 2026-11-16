#!/bin/bash
# 2.6.5: cross-compile libiris_edge0.so (edge0-infer + its own static ggml 0.24.0, all symbols hidden).
# Usage: EDGE0_DIR=/workspace/work/edge0/edge0-infer bash scripts/build-edge0.sh
set -eu
C=/workspace/work/compat
S=$(cd "$(dirname "$0")/.." && pwd)
E=${EDGE0_DIR:-/workspace/work/edge0/edge0-infer}
B=${B:-$C/edge0-build}
[ -f $B/build.ninja ] || cmake -G Ninja -S $S/app/src/main/cpp/edge0 -B $B -DCMAKE_TOOLCHAIN_FILE=$C/toolchain.cmake \
  -DCMAKE_BUILD_TYPE=Release -DEDGE0_DIR=$E -DBUILD_SHARED_LIBS=OFF \
  -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16 -DGGML_OPENMP=OFF -DGGML_BACKEND_DL=OFF \
  -DGGML_METAL=OFF -DGGML_BLAS=OFF -DGGML_ACCELERATE=OFF -DGGML_LLAMAFILE=OFF -DGGML_VULKAN=OFF -DGGML_OPENCL=OFF \
  -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF -DGGML_CPU_KLEIDIAI=OFF
ninja -C $B -j2 iris_edge0
llvm-strip-19 --strip-unneeded -o $S/prebuilt-native/arm64-v8a/libiris_edge0.so $B/libiris_edge0.so
llvm-nm-19 -D --defined-only $S/prebuilt-native/arm64-v8a/libiris_edge0.so | awk '{print $3}' | sort > $B/exports.txt
ls -l $S/prebuilt-native/arm64-v8a/libiris_edge0.so; wc -l < $B/exports.txt
echo EDGE0_DONE
