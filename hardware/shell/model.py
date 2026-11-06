"""谛听外壳 —— 参数化建模（build123d 0.13）。

部件：front / back / horn / eye / button
运行：/tmp/cadenv/bin/python model.py
"""
import math
import os

from build123d import (
    Align, Axis, Box, Cylinder, Face, Pos, Rot, Sphere, Vector, Wire,
    export_step, export_stl, extrude, fillet, loft,
)
from shapely.geometry import box as sbox

import params as K
import spr

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)
MIN = (Align.MIN, Align.MIN, Align.MIN)

OUTLINE = spr.body_outline()
CAV = spr.inset(OUTLINE, K.WALL)
R = K.EDGE_ROUND


# ------------------------------------------------------------ 基础工具
def ring_poly(d):
    return OUTLINE.buffer(-d, join_style=1, resolution=6) if d > 0 else OUTLINE


def face_of(poly, z):
    pts = [Vector(x, y, z) for x, y in poly.exterior.coords[:-1]]
    f = Face(Wire.make_polygon(pts, close=True))
    if f.normal_at().Z < 0:
        f = Face(Wire.make_polygon(pts[::-1], close=True))
    return f


def prism(poly, z0, z1):
    return extrude(face_of(poly, z0), amount=(z1 - z0), dir=(0, 0, 1))


def resample(poly, n=220):
    r = poly.exterior
    L = r.length
    return [(r.interpolate(i * L / n).x, r.interpolate(i * L / n).y) for i in range(n)]


def rounded_body():
    zs = [0.0, 0.65, 1.3, 1.95, R]
    layers = list(zs) + [K.D - z for z in reversed(zs)]
    faces = []
    for z in layers:
        d = R - math.sqrt(max(R * R - (R - min(z, K.D - z)) ** 2, 0.0))
        faces.append(Face(Wire.make_polygon(
            [Vector(x, y, z) for x, y in resample(ring_poly(d))], close=True)))
    return loft(faces, ruled=True)


def bbox(x0, y0, z0, x1, y1, z1):
    xa, xb = min(x0, x1), max(x0, x1)
    ya, yb = min(y0, y1), max(y0, y1)
    za, zb = min(z0, z1), max(z0, z1)
    return Box(xb - xa, yb - ya, zb - za, align=MIN).moved(Pos(xa, ya, za))


def cyl(cx, cy, z0, z1, r):
    return Pos(cx, cy, (z0 + z1) / 2) * Cylinder(r, z1 - z0)


def rounded_box(x0, y0, z0, x1, y1, z1, r):
    b = bbox(x0, y0, z0, x1, y1, z1)
    if r > 0:
        b = fillet(b.edges().filter_by(Axis.Z), r)
    return b


BODY = rounded_body()
print("body  vol=%.1f cm3 valid=%s" % (BODY.volume / 1000, BODY.is_valid))


# ------------------------------------------------------------ 前壳
def make_front():
    part = BODY & bbox(-200, -200, 0, 200, 200, K.SPLIT_Z)

    # 内腔
    part -= prism(CAV, K.WALL, K.SPLIT_Z + 0.5)

    # 搭接台阶（外轮廓内缩 LAP_T），z SPLIT-LAP .. SPLIT
    skin = (BODY - prism(ring_poly(K.LAP_STEP), -1.0, K.D + 1.0)) \
        & bbox(-200, -200, K.SPLIT_Z - K.LAP, 200, 200, K.SPLIT_Z + 0.5)
    part -= skin

    # 前脸整体内凹（凸边 = 像素描边）
    part -= prism(ring_poly(K.FACE_RIM), -2.0, K.FACE_RECESS)

    # 眼窝
    for xc in (-K.EYE_XC, K.EYE_XC):
        h = K.EYE_W / 2 + K.EYE_CLR
        part -= rounded_box(xc - h, K.EYE_YC - h, -2.0, xc + h, K.EYE_YC + h, K.EYE_POCKET, 1.0)

    # 咪头孔
    part -= cyl(K.MIC_C[0], K.MIC_C[1], -2.0, 6.0, K.MIC_HOLE / 2)

    # 按键：沉孔 + 通孔
    for c in (K.BTN_USER_C, K.BTN_RST_C):
        part -= cyl(c[0], c[1], -2.0, 2.1, K.BTN_POCKET_D / 2)
        part -= cyl(c[0], c[1], -2.0, 3.0, K.BTN_HOLE_D / 2)

    # 指示灯薄窗
    for (x0, y0, x1, y1) in (K.LED_PWR, K.LED_CRG):
        part -= prism(sbox(x0 - 0.5, y0 - 0.5, x1 + 0.5, y1 + 0.5), -2.0, 0.55)

    # ---- 板卡托架（侧壁 + 排针承托条 + 上下挡筋 + 压罩凸台）
    wx0, wx1 = K.TRAY_XW
    ty0, ty1 = K.TRAY_Y
    for sgn in (-1, 1):
        part += bbox(sgn * wx0, ty0, K.WALL, sgn * wx1, ty1, K.SPLIT_Z)
        part += bbox(sgn * K.TRAY_FLOOR_X, ty0, K.PIN_TIP_Z, sgn * wx1, ty1, K.SPLIT_Z)
    for sgn in (-1, 1):                                                  # 上挡块（躲开模组）
        part += bbox(sgn * 10.0, 77.7, K.WALL, sgn * wx1, 82.0, K.SPLIT_Z)
    px0, py0, pz0, px1, py1, pz1 = K.PAD_BOX                       # 压 T5-E1 屏蔽罩
    part += bbox(px0, py0, pz0, px1, py1, pz1)

    # ---- 底部开口（在托架之后切，顺便切掉挡筋）
    ux0, ux1 = K.USB_X
    uz0, uz1 = K.USB_Z
    part -= bbox(ux0, -4.0, uz0, ux1, 12.5, uz1)                  # USB-C
    part -= bbox(K.SW_X[0] - 0.6, -4.0, K.SW_Z[0] - 0.6,
                 K.SW_X[1] + 0.6, 17.0, K.SW_Z[1] + 0.6)          # 电源开关

    # ---- 顶部让位槽：T5-E1 天线端顶到 Y82.28，屋顶局部抬高
    part -= bbox(-10.2, 81.9, 4.5, 10.2, 82.9, 10.5)

    # ---- 芽的定位榫（前壳，从头顶壁向上伸出 3mm，尖角朝上可打）
    part += bbox(-6.0, 84.4, 0.55, 6.0, 87.5, 1.85)

    # ---- 卡点
    for sgn in (-1, 1):
        for y in (35.0, 60.0):
            part += Pos(sgn * 48.95, y, K.DETENT_Z) * Sphere(2.2)
    return part


# ------------------------------------------------------------ 后盖
def make_back():
    part = BODY - bbox(-200, -200, -200, 200, 200, K.SPLIT_Z - 0.001)
    part -= prism(CAV, K.WALL, K.D - K.WALL)

    # 裙边
    skirt = BODY & bbox(-200, -200, K.SPLIT_Z - K.LAP, 200, 200, K.SPLIT_Z - 0.001)
    skirt -= prism(ring_poly(K.LAP_T), K.SPLIT_Z - K.LAP - 1, K.SPLIT_Z)
    part += skirt

    # 4 道缝 → 4 个卡舌
    for y in (24.0, 47.5, 71.0):
        part -= bbox(-200, y - 0.8, K.SPLIT_Z - K.LAP - 0.5, 200, y + 0.8, K.SPLIT_Z - 0.01)

    # 卡点凹坑
    for sgn in (-1, 1):
        for y in (35.0, 60.0):
            part -= Pos(sgn * 48.95, y, K.DETENT_Z) * Sphere(2.25)

    # 电池挡筋
    for sgn in (-1, 1):
        xa = sgn * K.BAT_STOP_X
        xb = xa + sgn * K.BAT_STOP_T
        part += bbox(min(xa, xb), K.BAT_CY - 11.0, K.SPLIT_Z,
                     max(xa, xb), K.BAT_CY + 11.0, K.SPLIT_Z + 19.0)

    # 芽的定位榫（后盖）
    part += bbox(-6.0, 84.4, 42.15, 6.0, 87.5, 43.45)

    # 撬盖缺口
    part -= bbox(-8.0, -4.0, K.SPLIT_Z - 2.4, 8.0, 8.5, K.SPLIT_Z - 0.4)
    return part


# ------------------------------------------------------------ 芽
def make_horn():
    y0 = K.HORN_Y0
    hw = K.HORN_BASE_W / 2
    # 脚：沿深度贯通，坐在头顶壁的上沿
    foot = rounded_box(-hw, y0, 0.0, hw, y0 + 3.0, K.D, 1.1)
    # 台阶：26 宽 × 10 高
    base = rounded_box(-hw, y0 + 3.0, K.D / 2 - hw, hw, y0 + 13.0, K.D / 2 + hw, 2.0)
    # 触角：13 宽
    top = rounded_box(-K.HORN_TOP_W / 2, y0 + 11.0, K.D / 2 - K.HORN_TOP_W / 2,
                      K.HORN_TOP_W / 2, y0 + K.HORN_H, K.D / 2 + K.HORN_TOP_W / 2, 2.0)
    part = foot + base + top
    # 两条让位槽，套住前壳/后盖的定位榫
    for z in (1.2, 42.8):
        part -= bbox(-6.4, y0 - 0.2, z - 0.9, 6.4, y0 + 3.2, z + 0.9)
    return part


def make_eye():
    return rounded_box(-K.EYE_W / 2 + K.EYE_CLR, -K.EYE_W / 2 + K.EYE_CLR, 0.0,
                       K.EYE_W / 2 - K.EYE_CLR, K.EYE_W / 2 - K.EYE_CLR, K.EYE_T, 1.0)


def make_button():
    z0 = 0.95
    flange = cyl(0, 0, z0, z0 + K.BTN_CAP_T, K.BTN_CAP_D / 2)
    stem = cyl(0, 0, z0 + K.BTN_CAP_T - 0.05, z0 + K.BTN_CAP_T + K.BTN_STEM_L,
               K.BTN_STEM_D / 2)
    return flange + stem


def main():
    shapes = [("front", make_front()), ("back", make_back()), ("horn", make_horn()),
              ("eye", make_eye()), ("button", make_button())]
    for name, shape in shapes:
        print("%-7s vol=%8.2f cm3  valid=%s" % (name, shape.volume / 1000, shape.is_valid))
        export_stl(shape, os.path.join(OUT, name + ".stl"))
        export_step(shape, os.path.join(OUT, name + ".step"))
    print("exported to", OUT)


if __name__ == "__main__":
    main()
