cmake_minimum_required(VERSION 3.22.1)
project(beatster_audio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# core/ is platform independent and unit-tested on the host (see src/test/cpp);
# android/ adapts it to Oboe and JNI.
add_library(
        beatster_audio
        SHARED
        core/Buffers.cpp
        core/Engine.cpp
        core/EngineController.cpp
        android/OboeHost.cpp
        android/jni_bridge.cpp
)

target_compile_options(beatster_audio PRIVATE -Wall -Wextra -Wno-unused-parameter -O2)
# 16 KB page-size alignment for Android 15+ devices.
target_link_options(beatster_audio PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)

# Oboe ships as a Prefab package (com.google.oboe:oboe).
find_package(oboe REQUIRED CONFIG)

target_link_libraries(
        beatster_audio
        PRIVATE
        ${log-lib}
        oboe::oboe
        m
)
