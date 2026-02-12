cmake_minimum_required(VERSION 3.22.1)

project("live2davatarai")

set(APP_NAME live2davatarai)

# Path to the Core library
set(CSM_CORE_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(csm_core SHARED IMPORTED)
set_target_properties(csm_core PROPERTIES IMPORTED_LOCATION ${CSM_CORE_LIB_DIR}/libLive2DCubismCore.so)

# Framework sources
file(GLOB_RECURSE FRAMEWORK_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/Framework/*.cpp
)

# App sources (including common files)
file(GLOB APP_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp
)

add_library(${APP_NAME} SHARED
    ${FRAMEWORK_SOURCES}
    ${APP_SOURCES}
)

target_compile_definitions(${APP_NAME} PRIVATE
    CSM_TARGET_ANDROID_ES2
)

target_include_directories(${APP_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/Framework
)

target_link_libraries(${APP_NAME}
    csm_core
    GLESv2
    log
    android
)
