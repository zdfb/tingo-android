#!/usr/bin/env python3
"""从品牌图生成 Android 的图标与品牌图。

仓库里的 `res/mipmap-*/ic_launcher*.png` 与 `res/drawable-nodpi/tingo_mark.png`
都是这个脚本生成的产物——直接提交二进制而不留生成方式，别人就再也改不动它们。

用法（在 `app/android` 下）：`python3 scripts/gen-brand-assets.py`，
需要 Pillow（`python3 -m pip install pillow`）。
"""

import os
import sys

from PIL import Image

SOURCE = os.path.join("..", "..", "docs", "assets", "tingo-icon.png")
RES = os.path.join("app", "src", "main", "res")

# 传统图标：48dp 的基准，五档密度
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 自适应图标前景：108dp 的基准；内容缩到 66%，落在安全区内
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
SAFE_ZONE = 0.66
# 应用内品牌图：固定 160px，界面按 28dp 显示
MARK = 160


def main() -> int:
    if not os.path.isfile(SOURCE):
        print(f"找不到品牌图：{SOURCE}", file=sys.stderr)
        return 1
    art = Image.open(SOURCE).convert("RGBA")

    for density, size in LEGACY.items():
        out_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        art.resize((size, size), Image.LANCZOS).save(os.path.join(out_dir, "ic_launcher.png"))

    for density, size in ADAPTIVE.items():
        out_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        inner = int(size * SAFE_ZONE)
        scaled = art.resize((inner, inner), Image.LANCZOS)
        offset = (size - inner) // 2
        canvas.paste(scaled, (offset, offset), scaled)
        canvas.save(os.path.join(out_dir, "ic_launcher_foreground.png"))

    mark_dir = os.path.join(RES, "drawable-nodpi")
    os.makedirs(mark_dir, exist_ok=True)
    art.resize((MARK, MARK), Image.LANCZOS).save(os.path.join(mark_dir, "tingo_mark.png"))

    print("生成完毕：mipmap-*（1+2 张 × 5 档）、drawable-nodpi/tingo_mark.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
