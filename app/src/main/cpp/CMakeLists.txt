cmake_minimum_required(VERSION 3.22.1)

project(acousticguard_audio LANGUAGES CXX)

# This target intentionally uses the platform AAudio API and no third-party
# audio dependency is pulled into the APK.
add_library(acousticguard_audio SHARED acousticguard_audio.cpp)

target_compile_features(acousticguard_audio PRIVATE cxx_std_17)
target_compile_options(acousticguard_audio PRIVATE -Wall -Wextra -Wpedantic)

# AAudio is introduced in API 26. The Android module's minSdk is 26, while the
# Kotlin boundary still handles runtime/device stream-open failures explicitly.
target_link_libraries(acousticguard_audio PRIVATE aaudio log)
