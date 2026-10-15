cmake_minimum_required(VERSION 3.22.1)
project(beatster_audio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
        beatster_audio
        SHARED
        native_engine.cpp
)

find_library(
        log-lib
        log
)

# Find and link the Oboe library, which provides a wrapper for AAudio/OpenSL ES
find_package(oboe REQUIRED)

target_link_libraries(
        beatster_audio
        PUBLIC
        ${log-lib}
        oboe::oboe
        aaudio
        m
)
