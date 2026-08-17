cmake_minimum_required(VERSION 3.22.1)
project(omnicore_runtime LANGUAGES CXX)

add_library(
    omnicore_runtime
    SHARED
    native_bridge.cpp
    libretro_host_v6.cpp
)

target_compile_features(omnicore_runtime PRIVATE cxx_std_20)

target_compile_options(
    omnicore_runtime
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -O2
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

target_link_options(
    omnicore_runtime
    PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)

set_property(TARGET omnicore_runtime PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
target_include_directories(omnicore_runtime PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(android-lib android)
find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(dl-lib dl)

target_link_libraries(
    omnicore_runtime
    ${android-lib}
    ${log-lib}
    ${aaudio-lib}
    ${dl-lib}
)
