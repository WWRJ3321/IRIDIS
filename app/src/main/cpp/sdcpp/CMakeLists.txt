# IRIDIS 2.4.0: libiris_sd.so = upstream stable-diffusion.cpp (static, with its own ggml) + sd_jni.cpp.
# Built by scripts/build-sd-native.sh (CI: .github/workflows/native.yml), packaged from prebuilt-native/.
# Only Java_* is exported, so its ggml never clashes with libiris_llama's in the same process.
cmake_minimum_required(VERSION 3.22)
project(iris_sd C CXX)
set(CMAKE_CXX_STANDARD 17)
set(SD_DIR "" CACHE PATH "stable-diffusion.cpp checkout")
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${SD_DIR} sd)
add_library(iris_sd SHARED sd_jni.cpp)
target_include_directories(iris_sd PRIVATE ${SD_DIR}/include)
target_link_libraries(iris_sd PRIVATE stable-diffusion log android)
target_link_options(iris_sd PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map" -Wl,--gc-sections -Wl,-z,max-page-size=16384)
