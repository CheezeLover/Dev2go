cmake_minimum_required(VERSION 3.22)
project(dev2go LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# KleidiAI's Q8 kernels use the i8mm instructions on current ARMv8 phones; the plain NEON
# path leaves roughly half the matmul speed on the table. x86 (workstation probes) is
# unaffected.
if(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

# llama-server-impl only exists when llama.cpp is configured to build its tools, and
# these default to OFF for a library-style build. Gradle invokes this CMakeLists with no
# arguments of its own, so the flags have to be forced here or the link fails with
# "cannot find -lllama-server-impl".
set(LLAMA_BUILD_COMMON ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD    ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  ON  CACHE BOOL "" FORCE)

add_subdirectory(third_party/llama.cpp EXCLUDE_FROM_ALL)

# The runtime logic is a separate static target from the JNI shim so it can be built and
# exercised on a workstation against a real GGUF. The JNI file needs jni.h, which only
# exists in the NDK, so the two cannot be one library. This is what lets
# tools/dev2go_probe.cpp prove the server works without a phone or an emulator.
add_library(dev2go_server STATIC dev2go_server.cpp dev2go_server.h)
# Set per-target rather than relying on CMAKE_CXX_STANDARD: llama.cpp's own
# CMakeLists narrows the standard for its own targets, and the digit separators in
# ServerConfig need at least C++14.
target_compile_features(dev2go_server PUBLIC cxx_std_17)
target_include_directories(dev2go_server PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    third_party/llama.cpp/include
    third_party/llama.cpp/ggml/include
    third_party/llama.cpp/common
    third_party/llama.cpp/tools/cli
    third_party/llama.cpp/tools/server
    # common/http.h includes <cpp-httplib/httplib.h>, which resolves relative to
    # llama.cpp's vendor/ directory. The cpp-httplib target carries this privately,
    # so it does not reach a consumer of common/http.h like this one.
    third_party/llama.cpp/vendor
)
# llama-server-impl carries the route set. Pulling it in rather than reimplementing the
# HTTP surface is the whole point: tool-call grammar handling and chat templating stay
# upstream's.
target_link_libraries(dev2go_server PUBLIC llama-server-impl llama-common llama ggml cpp-httplib)

if(ANDROID)
    add_library(dev2go SHARED dev2go_jni.cpp)
    target_link_libraries(dev2go PRIVATE dev2go_server android log)
else()
    # Workstation build: runs the real server against a real model and checks the routes
    # an agent actually depends on. See tools/dev2go_probe.cpp.
    add_executable(dev2go_probe tools/dev2go_probe.cpp)
    target_link_libraries(dev2go_probe PRIVATE dev2go_server)
endif()