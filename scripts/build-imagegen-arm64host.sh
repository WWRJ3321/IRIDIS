#!/bin/sh
# 2.6.12: 在 arm64 Linux 主机上（官方 NDK 只有 x86_64 主机版）编 libiris_imagegen.so，放进 prebuilt-native/。
# 需要：NDK r27c 的 sysroot（只用头文件和 bionic 桩库）、pip 装的 ziglang（当 clang/lld 用）、third_party/qnn-api 头文件。
# 库不依赖 C++ 标准库，只链 libc/libdl/liblog/libandroid。
set -eu
cd "$(dirname "$0")/.."
NDK=${NDK:-/workspace/tools/android-ndk-r27c}
SR=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot
L=$SR/usr/lib/aarch64-linux-android/31
Z="python3 -m ziglang"
T=$(mktemp -d)
$Z c++ -target aarch64-linux-android -c -std=c++17 -O2 -fPIC -fvisibility=hidden -fno-exceptions -fno-rtti -Wall -Wextra \
  -isystem $SR/usr/include/aarch64-linux-android -isystem $SR/usr/include -Ithird_party/qnn-api/include \
  app/src/main/cpp/imagegen/qnn_imagegen.cpp -o $T/q.o
$Z ld.lld -shared -soname libiris_imagegen.so --gc-sections --icf=safe -z max-page-size=16384 -z noexecstack \
  --hash-style=gnu --build-id -s -o prebuilt-native/arm64-v8a/libiris_imagegen.so \
  $L/crtbegin_so.o $T/q.o -L$L -lc -ldl -llog -landroid $L/crtend_so.o
rm -rf $T
ls -l prebuilt-native/arm64-v8a/libiris_imagegen.so
