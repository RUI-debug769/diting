#!/usr/bin/env python3
"""从 ic_diting.svg 派生出全套图标位图与 Android 资源。

单一真源：同目录的 ic_diting.svg（1024×1024，纸底圆角方 + 环形缺口 + 九瓣 + 朱点）。
本脚本只做「换色 / 去底 / 缩放 / 切圆」，不再改动几何。

用法：
    python3 assets/brand/build_icons.py            # 生成 png/ 与 android 资源
    python3 assets/brand/build_icons.py --png-only # 只重出 png/

依赖：cairosvg（纯位图渲染）。建议装在独立 venv 里：
    python3 -m venv .venv-icons && .venv-icons/bin/pip install cairosvg
"""

from __future__ import annotations

import argparse
import io
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SVG = os.path.join(HERE, "ic_diting.svg")
RES = os.path.join(REPO, "android", "app", "src", "main", "res")

NS = "http://www.w3.org/2000/svg"

# 取色自设计稿（B 留白版 202px）
CREAM = "#F4EFE6"   # 纸底
INK = "#211D19"     # 墨（环 + 九瓣）
RED = "#B23A2E"     # 朱点
DARK_BG = "#1B1A18"  # 反色版底（与 App 的暖黑一致）
DARK_INK = "#EFEBE2"  # 反色版图形（暖白）
DARK_RED = "#CE4A38"  # 反色版朱点（提亮一档，暗底上不闷）

# adaptive icon：108dp 图层里只有中间 72dp 可见、66dp 安全。
# 扁平图标的设计是「1024 = 48dp，轮盘占 68.1%」，对应到 72dp 可见区即 49dp，
# 也就是在 108dp 图层里把图案缩到 68.1% × 72/108 = 45.4%（相对 1024 画布 ×0.6656）。
ADAPTIVE_SCALE = 0.6656

DENSITIES = [  # (目录后缀, 扁平图标边长 px, 前景图层边长 px)
    ("mdpi", 48, 108),
    ("hdpi", 72, 162),
    ("xhdpi", 96, 216),
    ("xxhdpi", 144, 324),
    ("xxxhdpi", 192, 432),
]


def read_parts():
    """读出 svg 里的可复用片段（保持原始数字精度，不重算几何）。"""
    tree = ET.parse(SVG)
    root = tree.getroot()
    paths = [el.get("d") for el in root.iter(f"{{{NS}}}path")]
    circle = next(el for el in root.iter(f"{{{NS}}}circle"))
    dot = (circle.get("cx"), circle.get("cy"), circle.get("r"))
    return paths, dot


def build_svg(paths, dot, *, plate, bg, ink, red, circle_plate=False, scale=1.0):
    cx, cy, r = dot
    out = [
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">',
    ]
    if plate == "rounded":
        out.append(f'<rect width="1024" height="1024" rx="233.2" fill="{bg}"/>')
    elif plate == "circle":
        out.append(f'<circle cx="512" cy="512" r="512" fill="{bg}"/>')
    if scale != 1.0:
        out.append(
            f'<g transform="translate(512 512) scale({scale}) translate(-512 -512)">'
        )
    out.append(f'<g fill="{ink}">')
    out += [f'<path d="{p}"/>' for p in paths]
    out.append("</g>")
    out.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{red}"/>')
    if scale != 1.0:
        out.append("</g>")
    out.append("</svg>")
    return "\n".join(out) + "\n"


def render(svg_text, px, out_path):
    import cairosvg

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    cairosvg.svg2png(
        bytestring=svg_text.encode("utf-8"),
        write_to=out_path,
        output_width=px,
        output_height=px,
    )
    return out_path


MASK_CIRCLE = '<circle cx="512" cy="512" r="341.33"/>'
MASK_SQUIRCLE = '<rect x="170.67" y="170.67" width="682.66" height="682.66" rx="155.7"/>' 
MASK_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
<defs><clipPath id="c">{shape}</clipPath></defs>
<g clip-path="url(#c)">
  <rect width="1024" height="1024" fill="{bg}"/>
  {art}
</g></svg>"""


def inner(svg_text):
    """剥掉 svg 根标签，保留内部元素，便于塞进 mask 里。"""
    body = svg_text.split(">", 1)[1]
    return body.rsplit("</svg>", 1)[0]


def write_repo_files(paths, dot):
    light = build_svg(paths, dot, plate="rounded", bg=CREAM, ink=INK, red=RED)
    dark = build_svg(paths, dot, plate="rounded", bg=DARK_BG, ink=DARK_INK, red=DARK_RED)
    mark = build_svg(paths, dot, plate=None, bg=None, ink=INK, red=RED)
    mark_dark = build_svg(paths, dot, plate=None, bg=None, ink=DARK_INK, red=DARK_RED)
    foreground = build_svg(paths, dot, plate=None, bg=None, ink=INK, red=RED,
                           scale=ADAPTIVE_SCALE)
    monochrome = build_svg(paths, dot, plate=None, bg=None, ink="#000000",
                           red="#000000", scale=ADAPTIVE_SCALE)

    for name, text in [
        ("ic_diting_dark.svg", dark),
        ("ic_diting_mark.svg", mark),
        ("ic_diting_mark_dark.svg", mark_dark),
    ]:
        with open(os.path.join(HERE, name), "w", encoding="utf-8") as f:
            f.write(text)

    return {
        "light": light,
        "dark": dark,
        "mark": mark,
        "mark_dark": mark_dark,
        "foreground": foreground,
        "monochrome": monochrome,
    }


def write_png(svgs):
    png = os.path.join(HERE, "png")
    # 扁平图标：与设计稿的尺寸阶梯一致
    for size in (1024, 512, 202, 192, 144, 96, 72, 48):
        render(svgs["light"], size, os.path.join(png, f"ic_diting_{size}.png"))
        render(svgs["dark"], size, os.path.join(png, f"ic_diting_dark_{size}.png"))
    # 透明底图形（给网页/文档/后续设计用）
    for size in (1024, 512, 192):
        render(svgs["mark"], size, os.path.join(png, f"ic_diting_mark_{size}.png"))
        render(svgs["mark_dark"], size, os.path.join(png, f"ic_diting_mark_dark_{size}.png"))
    print("png/ 已生成")


def write_android(svgs):
    paths, dot = PARTS
    round_svg = build_svg(paths, dot, plate="circle", bg=CREAM, ink=INK, red=RED)
    for suffix, flat_px, fg_px in DENSITIES:
        d = os.path.join(RES, f"mipmap-{suffix}")
        os.makedirs(d, exist_ok=True)
        render(svgs["light"], flat_px, os.path.join(d, "ic_launcher.png"))
        render(round_svg, flat_px, os.path.join(d, "ic_launcher_round.png"))
        render(svgs["foreground"], fg_px, os.path.join(d, "ic_launcher_foreground.png"))
        render(svgs["monochrome"], fg_px, os.path.join(d, "ic_launcher_monochrome.png"))

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
            f.write(adaptive)

    values = os.path.join(RES, "values")
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, "ic_launcher_colors.xml"), "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            "    <!-- 图标纸底。图标专用，不动 values/colors.xml 里的界面配色。 -->\n"
            f'    <color name="ic_launcher_background">{CREAM}</color>\n'
            "</resources>\n"
        )
    print("android 资源已生成")


PARTS = None  # (paths, dot)，main() 里填


def write_preview(svgs):
    """对照图：亮/暗两套的尺寸阶梯 + 自适应图标在实际遮罩下的样子。

    第三行是「模拟」：108dp 图层（1024 单位）里，图案按 ADAPTIVE_SCALE 缩到
    45.4%，再用直径 72dp（682.7 单位）的圆/方圆形遮罩裁切，最后把可见区放大
    回 202px —— 这样它应该和第一行 202px 的扁平图标看起来一样大。
    """
    from PIL import Image

    bg = "#1B2233"
    pad = 40
    ladder = [202, 144, 96, 72, 48]
    cell = 202
    width = pad * 2 + cell + 40 + sum(s + 24 for s in ladder)
    height = pad * 2 + 3 * cell + 40 * 2

    def tile(svg, size):
        buf = io.BytesIO()
        import cairosvg

        cairosvg.svg2png(
            bytestring=svg.encode("utf-8"),
            write_to=buf,
            output_width=size,
            output_height=size,
        )
        buf.seek(0)
        return Image.open(buf).convert("RGBA").copy()

    def mask_sim(shape):
        art = (
            f'<g transform="translate(512 512) scale({ADAPTIVE_SCALE}) '
            f'translate(-512 -512)">{inner(build_svg(*PARTS, plate=None, bg=None, ink=INK, red=RED))}</g>'
        )
        svg = MASK_SVG.format(shape=shape, bg=CREAM, art=art)
        img = tile(svg, 1024)
        half = 341.33
        box = (round(512 - half), round(512 - half), round(512 + half), round(512 + half))
        return img.crop(box).resize((cell, cell), Image.LANCZOS)

    canvas = Image.new("RGBA", (width, height), bg)
    y = pad
    for svg_text in (svgs["light"], svgs["dark"]):
        canvas.alpha_composite(tile(svg_text, cell), (pad, y))
        x = pad + cell + 40
        for s in ladder:
            canvas.alpha_composite(tile(svg_text, s), (x, y + cell - s))
            x += s + 24
        y += cell + 40

    x = pad
    for shape in (MASK_CIRCLE, MASK_SQUIRCLE):
        canvas.alpha_composite(mask_sim(shape), (x, y))
        x += cell + 40

    out = os.path.join(HERE, "preview.png")
    canvas.convert("RGB").save(out, optimize=True)
    print("preview.png 已生成")


def main():
    global PARTS
    ap = argparse.ArgumentParser()
    ap.add_argument("--png-only", action="store_true", help="只重出 assets/brand/png/")
    args = ap.parse_args()

    paths, dot = read_parts()
    PARTS = (paths, dot)
    svgs = write_repo_files(paths, dot)
    write_png(svgs)
    if not args.png_only:
        write_android(svgs)
        write_preview(svgs)


if __name__ == "__main__":
    try:
        main()
    except ImportError as exc:  # cairosvg / PIL 缺失时给出可执行的提示
        sys.exit(f"缺依赖：{exc}。先跑：python3 -m venv .venv-icons && "
                 f".venv-icons/bin/pip install cairosvg pillow")
