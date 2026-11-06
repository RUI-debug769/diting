# 谛听 · 图标

B 留白版：纸白圆角方底 + 墨色环（底部开口、右端收成尖锋尾）+ 九瓣辐条 + 朱点。
几何从设计稿的 202px 位图逐点还原成矢量，保留手绘的轻微不圆（环外径有约 3% 的椭圆度，
不是误差，是原稿的笔意）。

## 文件

| 文件 | 用途 |
| --- | --- |
| `ic_diting.svg` | 主图标（1024，含纸底），**几何真源** |
| `ic_diting_dark.svg` | 反色版（暖黑底、暖白图形、提亮的朱点） |
| `ic_diting_mark.svg` / `ic_diting_mark_dark.svg` | 去底的图形本体，1024 透明画布，给网页、文档、后续排版用 |
| `png/` | 由上面几个 SVG 渲染的位图：亮/暗各 1024/512/202/192/144/96/72/48，透明底 1024/512/192 |
| `preview.png` | 对照图：亮暗两套的尺寸阶梯 + 自适应图标在圆/方圆形遮罩下的模拟 |
| `build_icons.py` | 生成脚本：读 `ic_diting.svg`，换色、去底、缩放，产出 `png/` 与 Android 资源 |

## 色

| 名 | 值 | 用在哪 |
| --- | --- | --- |
| 纸 | `#F4EFE6` | 图标底 |
| 墨 | `#211D19` | 环 + 九瓣 |
| 朱 | `#B23A2E` | 圆心那一点 |
| 反色底 | `#1B1A18` | 暗色版底（和 App 的暖黑同一个） |
| 反色图形 | `#EFEBE2` | 暗色版环 + 九瓣 |
| 反色朱 | `#CE4A38` | 暗色版朱点 |

## 重新生成

```bash
python3 -m venv .venv-icons && .venv-icons/bin/pip install cairosvg pillow
.venv-icons/bin/python assets/brand/build_icons.py            # png + Android 资源 + 对照图
.venv-icons/bin/python assets/brand/build_icons.py --png-only # 只重出 png/
```

脚本会直接写 `android/app/src/main/res/` 下的 `mipmap-*` 与 `values/ic_launcher_colors.xml`，
再跑 `android/build_apk.sh` 就会进 APK。

## Android 这边怎么接的

- 启动图标：`mipmap-anydpi-v26/ic_launcher.xml` 是自适应图标（API 26+），
  `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` 是给老启动器的扁平位图。
- 自适应图标的图案单独渲染成 `ic_launcher_foreground.png`，按 **0.6656** 缩放：
  108dp 图层里只有中间 72dp 可见，扁平图标是「48dp 方块里轮盘占 68.1%」，
  换算到 108dp 图层就是 45.4%（68.1% × 72/108），这样两种图标在桌面上一样大。
- Android 13 起的「主题图标」用 `ic_launcher_monochrome.png`（单色，系统自己染色），
  它在深色桌面下会自动变浅色——这是深色模式下唯一能自动生效的形态。
- 普通启动图标**不会**跟随深色模式（Android 没有 night 限定的 launcher icon）；
  反色版是设计资产，用在文档、网页和需要暗底的排版里。
