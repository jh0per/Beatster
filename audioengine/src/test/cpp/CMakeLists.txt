# Host build of the platform-independent audio core and its tests (no Android SDK needed).
#   cmake -S audioengine/src/test/cpp -B build/enginetests -DCMAKE_BUILD_TYPE=Debug
#   cmake --build build/enginetests && build/enginetests/engine_tests
# Add -DBEATSTER_SANITIZE=address (or thread) to run under a sanitizer.
cmake_minimum_required(VERSION 3.22.1)
project(beatster_engine_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/core)

add_executable(engine_tests
        engine_tests.cpp
        ${CORE_DIR}/Buffers.cpp
        ${CORE_DIR}/Engine.cpp
        ${CORE_DIR}/EngineController.cpp)
target_include_directories(engine_tests PRIVATE ${CORE_DIR})
target_compile_options(engine_tests PRIVATE -Wall -Wextra -Wno-unused-parameter)
find_package(Threads REQUIRED)
target_link_libraries(engine_tests PRIVATE Threads::Threads)

set(BEATSTER_SANITIZE "" CACHE STRING "address, thread or empty")
if (BEATSTER_SANITIZE STREQUAL "address")
    target_compile_options(engine_tests PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(engine_tests PRIVATE -fsanitize=address,undefined)
elseif (BEATSTER_SANITIZE STREQUAL "thread")
    target_compile_options(engine_tests PRIVATE -fsanitize=thread)
    target_link_options(engine_tests PRIVATE -fsanitize=thread)
endif ()
