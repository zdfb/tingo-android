# 只编译 JNI 封装；libllama.so / libggml*.so 是预先交叉编译好的，
# 由 scripts/build-llama-android.sh 生成到 jniLibs（不进仓库）。
cmake_minimum_required(VERSION 3.22.1)
project(tingo_llama_jni CXX)

add_library(tingo_llama SHARED llama_jni.cpp)

target_include_directories(tingo_llama PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

set(LLAMA_LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${LLAMA_LIBS_DIR}/libllama.so)
add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION ${LLAMA_LIBS_DIR}/libggml.so)
add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION ${LLAMA_LIBS_DIR}/libggml-base.so)
add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION ${LLAMA_LIBS_DIR}/libggml-cpu.so)

target_link_libraries(tingo_llama llama ggml ggml-base ggml-cpu android log)
