#!/usr/bin/env bash
# 取内置语音模型到 app/android/models/（不进仓库，约 320 MB）。
#
# 优先从已连接的设备上取（那里通常已经下载并校验过），取不到再从镜像下载。
# 用法：
#   ./scripts/fetch-models-offline.sh                # 优先 adb，其次镜像
#   ./scripts/fetch-models-offline.sh --from-mirror   # 只走镜像
set -euo pipefail
cd "$(dirname "$0")/.."
MODELS_DIR="models"
mkdir -p "$MODELS_DIR"

ASR_FILES=(paraformer.int8.onnx tokens.txt am.mvn punct.int8.onnx campplus.onnx silero_vad.onnx)

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=io.github.tancheng33.tingo

have_all() {
  for f in "${ASR_FILES[@]}"; do
    [ -s "$MODELS_DIR/$f" ] || return 1
  done
}

if [ "${1:-}" != "--from-mirror" ] && have_all; then
  echo "模型已就位：$MODELS_DIR"
  exit 0
fi

if [ "${1:-}" != "--from-mirror" ] && [ -x "$ADB" ] && "$ADB" devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  device=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')
  echo "从设备 $device 取模型…"
  for f in "${ASR_FILES[@]}"; do
    "$ADB" -s "$device" shell "run-as $PKG cat cache/models-full/$f" > "$MODELS_DIR/$f"
  done
else
  echo "从镜像下载模型…"
  fetch() { # url size dest
    [ -s "$3" ] && [ "$(stat -f %z "$3" 2>/dev/null || stat -c %s "$3")" = "$2" ] && return 0
    curl -fL --retry 5 --retry-delay 3 -o "$3" "$1"
  }
  B=https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09/resolve/main
  fetch "$B/model.int8.onnx" 227330205 "$MODELS_DIR/paraformer.int8.onnx"
  fetch "$B/tokens.txt" 75354 "$MODELS_DIR/tokens.txt"
  fetch "$B/am.mvn" 11203 "$MODELS_DIR/am.mvn"
  fetch "https://hf-mirror.com/ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8/resolve/main/model.int8.onnx" 75519198 "$MODELS_DIR/punct.int8.onnx"
  fetch "https://hf-mirror.com/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx" 28281138 "$MODELS_DIR/campplus.onnx"
  fetch "$B/../silero_vad.onnx" 643854 "$MODELS_DIR/silero_vad.onnx"
fi

echo "模型清单："
ls -lh "$MODELS_DIR"
