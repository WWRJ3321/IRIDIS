cmake_minimum_required(VERSION 3.22.1)
project(iris_llama LANGUAGES C CXX)

# llama.cpp is fetched at a pinned commit into third_party/llama.cpp (see scripts/fetch-deps.sh).
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp" CACHE PATH "llama.cpp source dir")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}. Run: sh scripts/fetch-deps.sh")
endif ()

# ---- llama.cpp configuration: static, CPU only, tuned for Snapdragon 8 Elite (Oryon, ARMv8.7) ----
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# 2.5.0-beta9: llama.cpp common (speculative decoding / draft-mtp) is linked into libiris_llama only.
option(IRIS_SPEC "MTP speculative decoding via llama.cpp common (libiris_llama)" ON)
set(LLAMA_BUILD_COMMON ${IRIS_SPEC} CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)        # common's downloader is never used on device
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_HTML OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)          # ggml's own thread pool; no libomp to ship
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)          # cross-compiling: never probe the build host
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
# dotprod + i8mm + fp16 are present on every Snapdragon 8 Gen 1 and newer (incl. SM8750).
# The app checks /proc/cpuinfo before loading this library, so unsupported CPUs get a clear
# message instead of SIGILL.
# 2.5.0-beta8: -DIRIS_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 builds the no-i8mm libiris_llama_compat.so
# (with -DIRIS_KLEIDIAI=OFF); the default is unchanged.
set(IRIS_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "GGML_CPU_ARM_ARCH for this build")
set(GGML_CPU_ARM_ARCH "${IRIS_CPU_ARM_ARCH}" CACHE STRING "" FORCE)
option(IRIS_KLEIDIAI "Use Arm KleidiAI micro-kernels (downloads at configure time)" ON)
set(GGML_CPU_KLEIDIAI ${IRIS_KLEIDIAI} CACHE BOOL "" FORCE)

# ---- Adreno GPU via ggml's OpenCL backend ----
# The app never links the vendor libOpenCL.so directly: opencl/cl_shim.c forwards every cl* call
# to it through dlopen, so a device without an OpenCL driver simply reports "no GPU" and llama.cpp
# stays on the CPU instead of the whole library failing to load.
option(IRIS_OPENCL "Build ggml's OpenCL (Adreno) backend" ON)
if (IRIS_OPENCL)
    add_library(iris_cl_shim STATIC opencl/cl_shim.c)
    target_include_directories(iris_cl_shim PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/opencl)
    target_compile_options(iris_cl_shim PRIVATE -O2 -fvisibility=hidden)
    set(OpenCL_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencl" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY iris_cl_shim CACHE STRING "" FORCE)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
endif ()

# ---- 2.3.6: Mali / Immortalis / Xclipse GPU via ggml's Vulkan backend ----
# Off by default (needs Khronos Vulkan-Headers with vulkan.hpp, SPIRV-Headers and a host glslc;
# scripts/build-llama-native.sh and .github/workflows/native.yml pass them). libvulkan.so is part of
# every Android 7+ system image. At run time iris_engine.cpp keeps Vulkan off on Qualcomm SoCs
# (Adreno stays on OpenCL) and in crash-safe mode, via GGML_DISABLE_VULKAN, before the registry starts.
option(IRIS_VULKAN "Build ggml's Vulkan backend (non-Adreno GPUs)" OFF)
if (IRIS_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif ()

# ---- Whole-program optimisation ----
# ThinLTO across iris_llama + llama + ggml (+ KleidiAI): lets the linker inline across the static
# libraries and drop everything the JNI surface never reaches. Must be set before add_subdirectory
# so llama.cpp's targets are compiled to bitcode too. Assembly sources are left alone.
option(IRIS_LTO "ThinLTO for the native library" ON)
if (IRIS_LTO)
    add_compile_options($<$<COMPILE_LANGUAGE:C,CXX>:-flto=thin>)
endif ()

add_subdirectory(${LLAMA_DIR} llama.cpp EXCLUDE_FROM_ALL)

# 2.2.15: -DIRIS_HTP=ON builds libiris_lhtp.so instead (CPU + Hexagon NPU, JNI class LlamaHtpNative).
# ggml-base is linked whole-archive and exported: the upstream release libggml-hexagon.so (same
# llama.cpp commit, built with the Hexagon SDK) is patched to NEED libiris_lhtp.so instead of
# libggml-base.so (scripts/build-llama-htp.sh). Configure with IRIS_OPENCL=OFF IRIS_LTO=OFF.
option(IRIS_HTP "Build libiris_lhtp.so (Hexagon NPU variant)" OFF)
if (IRIS_HTP)
    add_library(iris_lhtp SHARED iris_engine.cpp jni_bridge.cpp)
    target_compile_features(iris_lhtp PRIVATE cxx_std_17)
    target_compile_definitions(iris_lhtp PRIVATE IRIS_HTP=1)
    target_compile_options(iris_lhtp PRIVATE -Wall -Wextra -O3)
    target_link_libraries(iris_lhtp PRIVATE "$<LINK_LIBRARY:WHOLE_ARCHIVE,ggml-base>" llama ggml android log dl)
    target_link_options(iris_lhtp PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif ()

add_library(iris_llama SHARED
        iris_engine.cpp
        jni_bridge.cpp)

# 2.2.17: llama.cpp tools/mtmd (libmtmd, static) for GGUF vision: mmproj + mtmd_helper. Only in
# libiris_llama; libiris_lhtp (IRIS_HTP) stays text-only and unchanged.
option(IRIS_MTMD "Link llama.cpp mtmd (GGUF vision) into libiris_llama" ON)
if (IRIS_MTMD)
    set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_DIR}/tools/mtmd mtmd EXCLUDE_FROM_ALL)
    target_compile_definitions(iris_llama PRIVATE IRIS_MTMD=1)
    target_link_libraries(iris_llama PRIVATE mtmd)
endif ()

target_compile_features(iris_llama PRIVATE cxx_std_17)
target_compile_options(iris_llama PRIVATE -Wall -Wextra -O3 -fvisibility=hidden)
target_link_libraries(iris_llama PRIVATE llama ggml android log)
if (IRIS_SPEC)
    target_compile_definitions(iris_llama PRIVATE IRIS_SPEC=1)
    target_link_libraries(iris_llama PRIVATE llama-common)
endif ()
if (IRIS_VULKAN)
    target_compile_definitions(iris_llama PRIVATE IRIS_VULKAN=1)
endif ()
if (IRIS_OPENCL)
    target_link_libraries(iris_llama PRIVATE iris_cl_shim dl)
endif ()
if (IRIS_LTO)
    # Keep -O3 code generation at link time (lld defaults to O2 for LTO codegen).
    target_link_options(iris_llama PRIVATE -flto=thin -Wl,--lto-O3)
endif ()
target_link_options(iris_llama PRIVATE
        -Wl,--gc-sections
        # Fold identical functions whose address is never taken (clang emits address-significance
        # tables by default, so this is the safe variant).
        -Wl,--icf=safe
        -Wl,--exclude-libs,ALL
        # 16 KB page-size alignment (required by Google Play for Android 15+ devices)
        -Wl,-z,max-page-size=16384)

# ---- MNN-LLM engine (full flavour: -DIRIS_MNN=ON) ----
# libMNN.a is MNN 3.6.1 cross-compiled by scripts/build-mnn-android.sh (CPU + ARMv8.2 FP16 +
# KleidiAI + OpenCL, LLM module, transformer fuse, low-memory weight dequant). It is linked
# whole-archive into one libiris_mnn.so: MNN registers its backends/ops from static initialisers,
# which a plain static link would drop.
option(IRIS_MNN "Build libiris_mnn.so (MNN-LLM engine)" OFF)
if (IRIS_MNN)
    set(IRIS_MNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mnn" CACHE PATH "prebuilt MNN")
    if (NOT EXISTS "${IRIS_MNN_DIR}/lib/${ANDROID_ABI}/libMNN.a")
        message(FATAL_ERROR "MNN not found at ${IRIS_MNN_DIR}. Run: sh scripts/build-mnn-android.sh")
    endif ()
    add_library(iris_mnn SHARED mnn/mnn_engine.cpp mnn/mnn_jni.cpp)
    target_compile_features(iris_mnn PRIVATE cxx_std_17)
    target_compile_options(iris_mnn PRIVATE -O2 -fvisibility=hidden -Wall)
    target_include_directories(iris_mnn PRIVATE ${IRIS_MNN_DIR}/include)
    target_link_libraries(iris_mnn PRIVATE
            -Wl,--whole-archive ${IRIS_MNN_DIR}/lib/${ANDROID_ABI}/libMNN.a -Wl,--no-whole-archive
            android log dl)
    target_link_options(iris_mnn PRIVATE
            -Wl,--gc-sections -Wl,--icf=safe -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
endif ()

# ---- 生图: libiris_imagegen.so (QNN context-binary runner for SD1.5 on the Hexagon NPU) ----
# Thin by design: loads libQnnHtp/libQnnSystem from the app's QNN runtime folder with dlopen and runs
# AI Hub context binaries; the diffusion loop is Kotlin (imagegen/QnnSdEngine.kt). Nothing Qualcomm is
# linked, so the library loads on every arm64 device. Needs only the QNN C API headers
# (third_party/qnn-api, fetched by scripts/fetch-deps.sh, never committed); without them the target is skipped
# and the app reports the NPU engine as unavailable.
option(IRIS_IMAGEGEN "Build libiris_imagegen.so (QNN image generation runner)" ON)
set(IRIS_QNN_API_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/qnn-api/include" CACHE PATH "QNN C API headers")
if (IRIS_IMAGEGEN)
    if (EXISTS "${IRIS_QNN_API_DIR}/QnnInterface.h")
        add_library(iris_imagegen SHARED imagegen/qnn_imagegen.cpp)
        target_compile_features(iris_imagegen PRIVATE cxx_std_17)
        target_compile_options(iris_imagegen PRIVATE -O2 -fvisibility=hidden -Wall -Wextra)
        target_include_directories(iris_imagegen PRIVATE ${IRIS_QNN_API_DIR})
        target_link_libraries(iris_imagegen PRIVATE android log dl)
        target_link_options(iris_imagegen PRIVATE
                -Wl,--gc-sections -Wl,--icf=safe -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
    else ()
        message(WARNING "QNN headers not found at ${IRIS_QNN_API_DIR}: skipping libiris_imagegen.so (run scripts/fetch-deps.sh)")
    endif ()
endif ()
