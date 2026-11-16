# 2.6.5: Edge0-35B engine (edge0-infer, Apache-2.0) as its own libiris_edge0.so.
# It carries its own static ggml 0.24.0; every symbol except JNI entry points is hidden
# (version script + -fvisibility=hidden + --exclude-libs) so it cannot clash with libiris_llama.so.
cmake_minimum_required(VERSION 3.22)
project(iris_edge0 LANGUAGES C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
if(NOT EDGE0_DIR)
  message(FATAL_ERROR "EDGE0_DIR must point to edge0/edge0-infer")
endif()
set(MOE_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(MOE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(MOE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${EDGE0_DIR} edge0)
add_library(iris_edge0 SHARED edge0_jni.cpp)
target_include_directories(iris_edge0 PRIVATE ${EDGE0_DIR}/third_party/nlohmann-json)
target_link_libraries(iris_edge0 PRIVATE edge0::edge0 android log)
target_link_options(iris_edge0 PRIVATE
  "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
  "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic" "-Wl,--gc-sections")
