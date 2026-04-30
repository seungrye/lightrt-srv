cmake_minimum_required(VERSION 3.22)
project(llama_jni)

set(LLAMA_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")

add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE ${LLAMA_INCLUDE_DIR})

# prebuilt .so 들을 임포트
set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

foreach(LIB llama llama-common ggml ggml-base ggml-cpu ggml-vulkan)
    add_library(${LIB} SHARED IMPORTED)
    set_target_properties(${LIB} PROPERTIES
        IMPORTED_LOCATION "${JNILIBS_DIR}/lib${LIB}.so")
endforeach()

target_link_libraries(llama_jni
    llama
    ggml
    ggml-base
    ggml-cpu
    ggml-vulkan
    android
    log)
