# SPDX-License-Identifier: Unlicense
cmake_minimum_required(VERSION 3.18)
project(sibalsa_native VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
option(SIBALSA_BUILD_TESTS "Build native host tests" ON)
option(SIBALSA_BUILD_CLI "Build host reference CLI" ON)
option(SIBALSA_SANITIZERS "Enable address and undefined behavior sanitizers" OFF)
option(SIBALSA_BUILD_JNI "Build JNI bridges on a host with a JDK" OFF)

if(ANDROID)
  # A single Android DSO owns all C++ objects, exceptions and static libc++ state.
  # The inference/session and trainer APIs remain independent JNI handle registries.
  add_library(sibalsa_android SHARED
    src/runtime.cpp src/sha256.cpp src/training.cpp
    src/jni_inference.cpp src/jni_training.cpp)
  target_include_directories(sibalsa_android PRIVATE include)
  target_compile_options(sibalsa_android PRIVATE -Wall -Wextra -Wpedantic
    -fvisibility=hidden -fvisibility-inlines-hidden)
  target_link_options(sibalsa_android PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,common-page-size=16384")
else()
  add_library(sibalsa_inference SHARED src/runtime.cpp src/sha256.cpp)
  target_include_directories(sibalsa_inference PUBLIC include)
  target_compile_options(sibalsa_inference PRIVATE -Wall -Wextra -Wpedantic)
  add_library(sibalsa_training SHARED src/training.cpp)
  target_link_libraries(sibalsa_training PUBLIC sibalsa_inference)
  target_include_directories(sibalsa_training PUBLIC include)
  target_compile_options(sibalsa_training PRIVATE -Wall -Wextra -Wpedantic)
endif()

if(SIBALSA_SANITIZERS AND NOT ANDROID)
  foreach(target sibalsa_inference sibalsa_training)
    target_compile_options(${target} PUBLIC -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(${target} PUBLIC -fsanitize=address,undefined)
  endforeach()
endif()

if(SIBALSA_BUILD_JNI AND NOT ANDROID)
  target_sources(sibalsa_inference PRIVATE src/jni_inference.cpp)
  target_sources(sibalsa_training PRIVATE src/jni_training.cpp)
  find_package(JNI REQUIRED)
  target_include_directories(sibalsa_inference PRIVATE ${JNI_INCLUDE_DIRS})
  target_include_directories(sibalsa_training PRIVATE ${JNI_INCLUDE_DIRS})
endif()

if(SIBALSA_BUILD_CLI AND NOT ANDROID)
  add_executable(sibalsa_native_cli src/cli.cpp tests/fixture.cpp)
  target_include_directories(sibalsa_native_cli PRIVATE tests)
  target_link_libraries(sibalsa_native_cli PRIVATE sibalsa_training)
endif()

if(SIBALSA_BUILD_TESTS AND NOT ANDROID)
  enable_testing()
  add_executable(sibalsa_native_tests tests/native_tests.cpp tests/fixture.cpp)
  target_link_libraries(sibalsa_native_tests PRIVATE sibalsa_training)
  target_compile_options(sibalsa_native_tests PRIVATE -Wall -Wextra -Wpedantic)
  add_test(NAME sibalsa_native_tests COMMAND sibalsa_native_tests)
endif()
