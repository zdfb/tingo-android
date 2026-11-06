#!/usr/bin/env bash
# 交叉编译 llama.cpp 的 Android 动态库（libllama.so / libggml*.so）与头文件。
#
# 产物不进仓库（约 6 MB 二进制）：谁要构建谁跑这个脚本。
# 用法： ./scripts/build-llama-android.sh [ABI...]   默认 arm64-v8a
set -euo pipefail

LLAMA_TAG="${LLAMA_TAG:-b12110}"
ABIS=("${@:-arm64-v8a}")
if [ "${#ABIS[@]}" -eq 0 ]; then ABIS=(arm64-v8a); fi

: "${ANDROID_NDK_HOME:?需要设置 ANDROID_NDK_HOME}"
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CPP_DIR="$APP_DIR/app/src/main/cpp"
JNI_DIR="$APP_DIR/app/src/main/jniLibs"
WORK="${LLAMA_WORK_DIR:-/tmp/llama-android-build}"

mkdir -p "$WORK"
if [ ! -d "$WORK/llama.cpp" ]; then
  echo "==> 下载 llama.cpp $LLAMA_TAG"
  curl -fsSL "https://gh-proxy.com/https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_TAG}.tar.gz" \
    -o "$WORK/llama.tar.gz"
  tar xzf "$WORK/llama.tar.gz" -C "$WORK"
  mv "$WORK/llama.cpp-${LLAMA_TAG}" "$WORK/llama.cpp"
fi

STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
[ -x "$STRIP" ] || STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

for abi in "${ABIS[@]}"; do
  echo "==> 编译 $abi"
  cmake -S "$WORK/llama.cpp" -B "$WORK/build-$abi" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF
  cmake --build "$WORK/build-$abi" --target llama -j "$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

  mkdir -p "$JNI_DIR/$abi"
  for lib in libllama.so libggml.so libggml-base.so libggml-cpu.so; do
    cp "$WORK/build-$abi/bin/$lib" "$JNI_DIR/$abi/$lib"
    "$STRIP" --strip-unneeded "$JNI_DIR/$abi/$lib"
  done
done

mkdir -p "$CPP_DIR/include"
cp "$WORK/llama.cpp/include/llama.h" "$CPP_DIR/include/"
cp "$WORK/llama.cpp/ggml/include/"*.h "$CPP_DIR/include/"

echo "==> 完成：$JNI_DIR/<abi>/lib*.so 与 $CPP_DIR/include/"
