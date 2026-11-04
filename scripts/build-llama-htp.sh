#!/usr/bin/env sh
# 2.2.15: Hexagon NPU variant of the GGUF engine -> prebuilt-assets/htp/ (full-flavour assets).
#  - libiris_lhtp.so: our JNI + llama.cpp (third_party/llama.cpp, see fetch-deps.sh) built with the NDK, CPU only,
#    ggml-base exported.
#  - libiris-hexagon.so + libiris-htp-v73..v85.so: ggml-hexagon host + HTP skels from the official
#    llama.cpp release b12099 (bee2332, the same commit as fetch-deps.sh; 2.3.6), built by
#    upstream CI with the Hexagon SDK. Renamed so they never collide with GenieX's older copies:
#    host soname libggml-hexagon.so -> libiris-hexagon.so, NEEDED libggml-base.so -> libiris_lhtp.so,
#    skel URI/soname libggml-htp-vNN.so -> libiris-htp-vNN.so (same length, in-place).
# Usage: NDK=/path/to/android-ndk sh scripts/build-llama-htp.sh   (needs patchelf, curl)
set -eu
cd "$(dirname "$0")/.."
NDK="${NDK:?set NDK=/path/to/android-ndk}"
REL=b12099
BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
B=build-native-htp; rm -rf "$B"
cmake -G Ninja -S app/src/main/cpp -B "$B" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DIRIS_KLEIDIAI=ON -DIRIS_LTO=OFF -DIRIS_OPENCL=OFF \
  -DIRIS_IMAGEGEN=OFF -DIRIS_MNN=OFF -DIRIS_HTP=ON -DLLAMA_DIR="$PWD/third_party/llama.cpp"
ninja -C "$B" iris_lhtp
OUT=prebuilt-assets/htp; rm -rf "$OUT"; mkdir -p "$OUT"
"$BIN/llvm-strip" --strip-unneeded -o "$OUT/libiris_lhtp.so" "$B/libiris_lhtp.so"
T=$(mktemp -d)
curl -fsSL -o "$T/r.tgz" "https://github.com/ggml-org/llama.cpp/releases/download/$REL/llama-$REL-bin-android-arm64-snapdragon.tar.gz"
tar xzf "$T/r.tgz" -C "$T"
L="$T/llama-$REL/lib"
"$BIN/llvm-strip" --strip-unneeded -o "$OUT/libiris-hexagon.so" "$L/libggml-hexagon.so"
patchelf --set-soname libiris-hexagon.so --replace-needed libggml-base.so libiris_lhtp.so "$OUT/libiris-hexagon.so"
python3 - "$OUT" "$L" <<'PY'
import sys, os
out, lib = sys.argv[1], sys.argv[2]
def sub(data, a, b):
    assert len(a) == len(b); n = data.count(a)
    if n == 0: print("note: not found", a)
    return data.replace(a, b), n
p = os.path.join(out, 'libiris-hexagon.so')
d, n = sub(open(p, 'rb').read(), b'file:///libggml-htp-v%u.so', b'file:///libiris-htp-v%u.so'); open(p, 'wb').write(d)
for v in (73, 75, 79, 81, 85):
    s = open(os.path.join(lib, f'libggml-htp-v{v}.so'), 'rb').read()
    s, _ = sub(s, f'libggml-htp-v{v}.so'.encode(), f'libiris-htp-v{v}.so'.encode())
    open(os.path.join(out, f'libiris-htp-v{v}.so'), 'wb').write(s)
PY
echo "$REL" > "$OUT/VERSION"
rm -rf "$T"
ls -la "$OUT"
