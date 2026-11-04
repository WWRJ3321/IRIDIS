#!/usr/bin/env sh
# Fetches pinned third-party dependencies that are not on Maven:
#   - llama.cpp source (built from source by Gradle/CMake into libiris_llama.so)
#   - sherpa-onnx Android AAR (speech recognition / synthesis / VAD)
#   - MNN prebuilt static lib for the full flavour (third_party/mnn; ~6 MB tar.gz, SHA-256 pinned).
#     Pass --build-mnn to compile it from source instead (needs NDK r30, 15-30 min).
#   - QNN 2.50 C API headers (third_party/qnn-api/include) for libiris_imagegen.so, taken from
#     Qualcomm's geniex-qairt-plugin repo at a pinned commit (headers only; never committed here).
# Re-running is safe: existing, verified copies are kept.
set -eu
cd "$(dirname "$0")/.."

LLAMA_REPO="https://github.com/ggml-org/llama.cpp"
LLAMA_COMMIT="bee23322f1baa3d2dd16b8339bed6aff4a8eb663"  # b12099, 2026-11-04 (Gemma 4 graph opt, OpenCL IQ4_XS/topk_mask, Hexagon TOP_K)

SHERPA_VERSION="1.13.8"
SHERPA_SHA256="b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
SHERPA_AAR="app/libs/sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar"

if [ -L third_party/llama.cpp ] || [ -f third_party/llama.cpp/CMakeLists.txt ]; then
  echo "llama.cpp: present"
else
  echo "llama.cpp: fetching $LLAMA_COMMIT"
  mkdir -p third_party/llama.cpp
  git -C third_party/llama.cpp init --quiet
  git -C third_party/llama.cpp fetch --quiet --depth 1 "$LLAMA_REPO" "$LLAMA_COMMIT"
  git -C third_party/llama.cpp checkout --quiet FETCH_HEAD
fi
actual=$(git -C third_party/llama.cpp rev-parse HEAD)
if [ "$actual" != "$LLAMA_COMMIT" ]; then
  echo "llama.cpp: expected $LLAMA_COMMIT but found $actual" >&2
  exit 1
fi

sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
if [ -f "$SHERPA_AAR" ] && [ "$(sha "$SHERPA_AAR")" = "$SHERPA_SHA256" ]; then
  echo "sherpa-onnx: present"
else
  echo "sherpa-onnx: downloading $SHERPA_VERSION"
  mkdir -p app/libs
  curl -fL --retry 3 -o "$SHERPA_AAR" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar"
  [ "$(sha "$SHERPA_AAR")" = "$SHERPA_SHA256" ] || { echo "sherpa-onnx: checksum mismatch" >&2; exit 1; }
fi

# ---- MNN prebuilt (full flavour, third_party/mnn) ----
MNN_COMMIT="9f097c5fdd48e0cac4468d4cc53a828794d96888"
MNN_TAR_SHA256="b275016e22db724cdac033749a384e3a729540a4a722cf4e6be640cd9a0360c2"
MNN_URL="https://github.com/WWRJ3321/IRIDIS/releases/download/v2.0.0-beta/mnn-prebuilt-arm64.tar.gz"
if [ "${1:-}" = "--build-mnn" ]; then
  NDK="${NDK:?set NDK=/path/to/android-ndk-r30}" sh scripts/build-mnn-android.sh
else
  if [ -f third_party/mnn/lib/arm64-v8a/libMNN.a ] && grep -q "$MNN_COMMIT" third_party/mnn/VERSION 2>/dev/null; then
    echo "MNN: present"
  else
    echo "MNN: downloading prebuilt ($MNN_COMMIT)"
    rm -rf third_party/mnn third_party/mnn.tmp
    curl -fL --retry 3 -o /tmp/mnn-prebuilt.tar.gz "$MNN_URL"
    [ "$(sha /tmp/mnn-prebuilt.tar.gz)" = "$MNN_TAR_SHA256" ] || { echo "MNN: checksum mismatch" >&2; exit 1; }
    mkdir -p third_party
    tar -xzf /tmp/mnn-prebuilt.tar.gz -C third_party
    grep -q "$MNN_COMMIT" third_party/mnn/VERSION || { echo "MNN: VERSION mismatch" >&2; exit 1; }
  fi
fi
# ---- QNN C API headers (libiris_imagegen.so) ----
QNN_API_REPO="https://github.com/qualcomm/geniex-qairt-plugin"
QNN_API_COMMIT="2c3d4fcd2e3c8c917da6308c1459ccf83086b29f"
if [ -f third_party/qnn-api/include/QnnInterface.h ] && [ -f third_party/qnn-api/include/System/QnnSystemInterface.h ]; then
  echo "qnn-api headers: present"
else
  echo "qnn-api headers: fetching geniex-qairt-plugin@$QNN_API_COMMIT"
  rm -rf third_party/qnn-api third_party/.qnn-api.tmp
  mkdir -p third_party/.qnn-api.tmp
  git -C third_party/.qnn-api.tmp init --quiet
  git -C third_party/.qnn-api.tmp remote add origin "$QNN_API_REPO"
  git -C third_party/.qnn-api.tmp fetch --quiet --depth 1 origin "$QNN_API_COMMIT"
  git -C third_party/.qnn-api.tmp checkout --quiet FETCH_HEAD
  [ "$(git -C third_party/.qnn-api.tmp rev-parse HEAD)" = "$QNN_API_COMMIT" ] || { echo "qnn-api: commit mismatch" >&2; exit 1; }
  mkdir -p third_party/qnn-api
  cp -R third_party/.qnn-api.tmp/qnn-api/include third_party/qnn-api/include
  rm -rf third_party/.qnn-api.tmp
  [ -f third_party/qnn-api/include/QnnInterface.h ] || { echo "qnn-api: headers missing" >&2; exit 1; }
fi
echo "done"
