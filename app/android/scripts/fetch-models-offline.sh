#!/usr/bin/env bash
# 取内置语音模型到 app/android/models/（不进仓库，约 340 MB）。
#
# 与桌面端同一份模型：ModelScope 的 Paraformer-large 量化版（int8），
# 外加 CT-Transformer 标点 int8、CAM++ 声纹、Silero VAD。
# ModelScope 的原文件不能直接被 sherpa-onnx 加载（缺 LFR/归一化元数据），
# 所以下载后要补元数据、把它自带的 tokens.json 展开成 "符号 编号" 的 tokens.txt——
# 这一步桌面端在首次启动时由 `engine::models::prepare_paraformer` 完成，
# 这里用等价的 python 实现，免得为了取模型还要编一遍 Rust/sherpa。
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
# 补过元数据后的模型体积；和 models.json 里记的一致
PARAFORMER_BYTES=238402431

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
  # 识别模型：桌面端用的就是这三个文件
  B=https://modelscope.cn/models/damo/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-onnx/resolve/master
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  fetch "$B/model_quant.onnx" 238380216 "$TMP/paraformer.onnx"
  fetch "$B/am.mvn" 11203 "$MODELS_DIR/am.mvn"
  fetch "$B/tokens.json" 93676 "$TMP/tokens.json"

  # 补元数据 + 展开词表（等价于 engine::models::prepare_paraformer）
  python3 - "$TMP/paraformer.onnx" "$TMP/tokens.json" "$MODELS_DIR/am.mvn" \
    "$MODELS_DIR/paraformer.int8.onnx" "$MODELS_DIR/tokens.txt" <<'PYTHON'
import json, shutil, sys

model_src, tokens_json, mvn_path, model_dst, tokens_dst = sys.argv[1:6]
mvn = open(mvn_path, encoding="utf-8", errors="replace").read()

def vector(tag):
    body = mvn.split(tag, 1)[1].split("[", 1)[1].split("]", 1)[0]
    return [float(x) for x in body.split()]

def join(values):
    # Rust 的 f32 to_string 是"最短往返表示"；解析回来都是同一个 float32
    import struct
    out = []
    for v in values:
        f = struct.unpack("<f", struct.pack("<f", v))[0]
        text = repr(f)
        out.append(text[:-2] if text.endswith(".0") else text)
    return ",".join(out)

def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n == 0:
            out.append(b)
            return bytes(out)
        out.append(b | 0x80)

def entry(key, value):
    body = bytearray()
    for field, text in ((1, key), (2, value)):
        body.append((field << 3) | 2)
        body += varint(len(text.encode()))
        body += text.encode()
    out = bytearray([(14 << 3) | 2])
    out += varint(len(body))
    out += body
    return bytes(out)

tokens = json.load(open(tokens_json, encoding="utf-8"))
meta = [
    ("lfr_window_size", "7"),
    ("lfr_window_shift", "6"),
    ("neg_mean", join(vector("<AddShift>"))),
    ("inv_stddev", join(vector("<Rescale>"))),
    ("vocab_size", str(len(tokens))),
    ("model_type", "paraformer"),
    ("version", "1"),
]

with open(tokens_dst, "w", encoding="utf-8") as f:
    for i, token in enumerate(tokens):
        f.write(f"{token} {i}\n")

shutil.copyfile(model_src, model_dst)
with open(model_dst, "ab") as f:
    for key, value in meta:
        f.write(entry(key, value))
print(f"已生成 {model_dst}（{len(tokens)} 个词表项）")
PYTHON

  fetch "https://hf-mirror.com/ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8/resolve/main/model.int8.onnx" 75519198 "$MODELS_DIR/punct.int8.onnx"
  fetch "https://hf-mirror.com/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx" 28281138 "$MODELS_DIR/campplus.onnx"
  fetch "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09/resolve/main/silero_vad.onnx" 643854 "$MODELS_DIR/silero_vad.onnx"

  if [ "$(stat -f %z "$MODELS_DIR/paraformer.int8.onnx" 2>/dev/null || stat -c %s "$MODELS_DIR/paraformer.int8.onnx")" != "$PARAFORMER_BYTES" ]; then
    echo "识别模型体积不对：期望 $PARAFORMER_BYTES" >&2
    exit 1
  fi
fi

echo "模型清单："
ls -lh "$MODELS_DIR"
