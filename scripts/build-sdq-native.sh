#!/usr/bin/env sh
# IRIDIS 2.4.3: Qualcomm Hexagon (HTP v79+) image-generation engine -> prebuilt-assets/sdq/
#  - libiris_sdq.so : app/src/main/cpp/sdcpp (IRIS_SDQ) + stable-diffusion.cpp Qualcomm branch
#    (happyyzy/stable-diffusion.cpp work/qualcomm-hexagon-optimizations = leejet/stable-diffusion.cpp PR #1970, MIT)
#    with its ggml (happyyzy/ggml, MIT) built with GGML_HEXAGON=ON, CPU fallback. Static ggml, only Java_* exported.
#  - libirsd-htp-v79.so / v81.so : the matching HTP skels, renamed in place (libggml-htp -> libirsd-htp, same length)
#    so they never collide with the llama.cpp (libiris-htp-*) or GenieX copies in the same process.
# Needs the Hexagon SDK: run inside ghcr.io/snapdragon-toolchain/arm64-android (HEXAGON_SDK_ROOT, ANDROID_NDK_ROOT).
set -eu
cd "$(dirname "$0")/.."
NDK="${NDK:-${ANDROID_NDK_ROOT:?set NDK}}"
: "${HEXAGON_SDK_ROOT:?set HEXAGON_SDK_ROOT}"
SDQ_COMMIT="854c9d1019775a3565c819362ac2dec29675911e"   # happyyzy/stable-diffusion.cpp 2026-10-31
SD=third_party/stable-diffusion.cpp-qc
if [ "$(git -C "$SD" rev-parse HEAD 2>/dev/null || true)" != "$SDQ_COMMIT" ]; then
  rm -rf "$SD"; mkdir -p "$SD"
  git -C "$SD" init -q
  git -C "$SD" remote add origin https://github.com/happyyzy/stable-diffusion.cpp
  git -C "$SD" fetch -q --depth 1 origin "$SDQ_COMMIT"
  git -C "$SD" checkout -q FETCH_HEAD
  git -C "$SD" submodule update -q --init --depth 1 ggml || git -C "$SD" submodule update -q --init ggml
fi
B=build-sdq; rm -rf "$B"
cmake -G Ninja -S app/src/main/cpp/sdcpp -B "$B" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm+fp16 \
  -DSD_DIR="$PWD/$SD" -DIRIS_SDQ=ON -DSD_VULKAN=OFF -DGGML_HEXAGON=ON \
  -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" ${HEXAGON_TOOLS_ROOT:+-DHEXAGON_TOOLS_ROOT="$HEXAGON_TOOLS_ROOT"} -DPREBUILT_LIB_DIR=android_aarch64
ninja -C "$B" iris_sdq htp-v79 htp-v81
BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
OUT=prebuilt-assets/sdq; rm -rf "$OUT"; mkdir -p "$OUT"
"$BIN/llvm-strip" --strip-unneeded -o "$OUT/libiris_sdq.so" "$B/libiris_sdq.so"
python3 - "$OUT" "$(find "$B" -name 'libggml-htp-v79.so' -printf '%h\n' | head -1)" <<'PY'
import sys, os
out, lib = sys.argv[1], sys.argv[2]
def sub(d, a, b):
    assert len(a) == len(b); print(a, d.count(a)); return d.replace(a, b)
p = os.path.join(out, 'libiris_sdq.so')
d = open(p, 'rb').read(); assert b'file:///libggml-htp-v%u.so' in d
open(p, 'wb').write(sub(d, b'file:///libggml-htp-v%u.so', b'file:///libirsd-htp-v%u.so'))
for v in (79, 81):
    s = open(os.path.join(lib, f'libggml-htp-v{v}.so'), 'rb').read()
    open(os.path.join(out, f'libirsd-htp-v{v}.so'), 'wb').write(sub(s, f'libggml-htp-v{v}.so'.encode(), f'libirsd-htp-v{v}.so'.encode()))
PY
git -C "$SD" rev-parse --short HEAD > "$OUT/VERSION"
ls -la "$OUT"
