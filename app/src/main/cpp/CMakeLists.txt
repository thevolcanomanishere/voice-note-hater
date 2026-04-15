cmake_minimum_required(VERSION 3.22)
project(watranscribe-jni)

find_library(LOG_LIB log)

add_library(watranscribe-jni SHARED jni.c)

# Link against the prebuilt whisper shared library
add_library(whisper SHARED IMPORTED)
set_target_properties(whisper PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwhisper.so
)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so
)

target_include_directories(watranscribe-jni PRIVATE
    /tmp/whisper.cpp/include
    /tmp/whisper.cpp/ggml/include
)

target_link_libraries(watranscribe-jni ${LOG_LIB} whisper ggml)
