#!/usr/bin/env bash
# 2.3.0 ③ 本地翻译: builds libiris_bergamot.so (arm64-v8a) = bergamot-translator (MPL-2.0) + our
# JNI wrapper app/src/main/cpp/bergamot/bergamot_jni.cpp. Output goes to prebuilt-native/arm64-v8a
# (packaged with -Piris.prebuiltNative=prebuilt-native like the other native libs).
# Needs: cmake ≥ 3.20, ninja, git, NDK (r27+; built with r30). ~40 min on 2 cores. ~7.8 MB stripped.
set -euo pipefail
NDK=${NDK:-/workspace/tools/android-ndk-r30}
SRC=${BERGAMOT_SRC:-$PWD/third_party/bergamot-translator}
APP=$(cd "$(dirname "$0")/.." && pwd)
BMT_REV=9271618ebbdc5d21ac4dc4df9e72beb7ce644774   # browsermt/bergamot-translator main
# submodules at that revision: marian-dev 2781d735d4a1, ssplit-cpp a311f9865ade

if [ ! -d "$SRC" ]; then
  git clone https://github.com/browsermt/bergamot-translator "$SRC"
  git -C "$SRC" checkout $BMT_REV
  git -C "$SRC" submodule update --init --recursive --depth 1 3rd_party/marian-dev 3rd_party/ssplit-cpp
fi

# Patches (idempotent):
# 1) pcre2 external project: use Ninja (no make in the build env) + cmake 4 policy floor.
sed -i 's#CONFIGURE_COMMAND ${CMAKE_COMMAND} ${PCRE2_SRC_DIR} ${PCRE2_CONFIGURE_OPTIONS}#CONFIGURE_COMMAND ${CMAKE_COMMAND} -G Ninja -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM} -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ${PCRE2_SRC_DIR} ${PCRE2_CONFIGURE_OPTIONS}#' \
  "$SRC/3rd_party/ssplit-cpp/cmake/FindPCRE2.cmake"
# 2) sentencepiece trainer: newer clang rejects a constexpr cast of -1 to an enum.
sed -i 's/constexpr unicode_script::ScriptType kAnyType/const unicode_script::ScriptType kAnyType/' \
  "$SRC/3rd_party/marian-dev/src/3rd_party/sentencepiece/src/trainer_interface.cc"
# 3) our JNI target.
grep -q iris_bergamot "$SRC/CMakeLists.txt" || cat >> "$SRC/CMakeLists.txt" <<'EOF'

# IRIDIS: JNI wrapper (app/src/main/cpp/bergamot/bergamot_jni.cpp)
if(ANDROID AND IRIS_JNI_SRC)
  add_library(iris_bergamot SHARED ${IRIS_JNI_SRC})
  target_link_libraries(iris_bergamot PRIVATE bergamot-translator log)
  target_link_options(iris_bergamot PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -s -Wl,-z,max-page-size=16384)
  set_target_properties(iris_bergamot PROPERTIES CXX_VISIBILITY_PRESET hidden)
endif()
EOF

B="$SRC/build-android"; mkdir -p "$B/pcre2/src/pcre2-build"; cd "$B"
cmake .. -G Ninja -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DBUILD_ARCH=armv8.2-a+dotprod \
  -DUSE_MKL=OFF -DCOMPILE_LIBRARY_ONLY=ON -DSSPLIT_USE_INTERNAL_PCRE2=ON -DUSE_DOXYGEN=OFF -DCOMPILE_UNIT_TESTS=OFF \
  -DGIT_SUBMODULE=OFF -DUSE_TCMALLOC=OFF -DCOMPILE_TESTS=OFF \
  -DIRIS_JNI_SRC="$APP/app/src/main/cpp/bergamot/bergamot_jni.cpp"
ninja -j"$(nproc)" iris_bergamot
mkdir -p "$APP/prebuilt-native/arm64-v8a"
cp libiris_bergamot.so "$APP/prebuilt-native/arm64-v8a/"
echo "OK: $APP/prebuilt-native/arm64-v8a/libiris_bergamot.so"
