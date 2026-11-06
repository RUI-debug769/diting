"""装配干涉校验：官方板（用 STEP 子件包围盒做代理）/ 电池 / 分件之间。

板件坐标系：Xs 宽 0..28.04, Ys 长 0..67.5（0=USB）, Zs 0=PCB 元件面
模型坐标：X = Xs-14.02, Y = Ys+10, Z = 9-Zs
"""
import build123d as bd
from build123d import Pos, Rot, Cylinder

import model as M
import params as K

# (Xs0, Xs1, Ys0, Ys1, Zs0, Zs1, 名称)
PARTS = [
    (0.00, 28.04, 0.00, 67.50, -1.61, 0.00, "PCB"),
    (0.02, 2.52, 11.66, 67.24, -10.51, 1.39, "排针L"),
    (25.52, 28.02, 11.66, 67.24, -10.51, 1.39, "排针R"),
    (5.02, 23.02, 47.98, 73.48, -0.01, 2.80, "T5-E1"),
    (7.58, 15.43, -1.11, 4.14, -0.50, 1.50, "USB-C"),
    (0.30, 5.55, 1.51, 9.16, 0.00, 3.76, "BAT座"),
    (17.79, 26.73, 0.09, 8.04, -0.91, 3.25, "开关"),
    (4.52, 10.52, 36.55, 42.55, -5.00, 3.18, "咪头"),
    (16.94, 21.94, 35.85, 38.78, 0.00, 2.00, "USER键"),
    (16.94, 21.94, 41.12, 44.05, 0.00, 2.00, "RST键"),
    (6.21, 10.21, 13.05, 17.05, 0.00, 2.00, "键89"),
    (14.23, 18.23, 13.05, 17.05, 0.00, 2.00, "键16"),
    (11.23, 15.73, 31.66, 34.96, -2.00, 4.80, "最高件97"),
    (21.88, 22.94, 8.50, 10.40, 0.00, 1.50, "件94"),
    (14.02, 15.62, 35.18, 35.98, 0.00, 0.77, "LED_PWR"),
    (11.35, 12.95, 35.14, 35.94, 0.00, 0.77, "LED_CRG"),
    (17.18, 18.78, 9.59, 10.39, 0.00, 0.77, "LED38"),
    (19.61, 21.21, 9.59, 10.39, 0.00, 0.77, "LED39"),
]


def proxy_board():
    acc = None
    for xs0, xs1, ys0, ys1, zs0, zs1, _ in PARTS:
        b = M.bbox(xs0 - K.BOARD_XC, ys0 + K.BOARD_Y0, K.BOARD_Z0 - zs1,
                   xs1 - K.BOARD_XC, ys1 + K.BOARD_Y0, K.BOARD_Z0 - zs0)
        acc = b if acc is None else acc + b
    return acc


def battery():
    return Pos(0, 40.0, 31.8) * Rot(0, 90, 0) * Cylinder(K.BAT_D / 2, K.BAT_L)


def report(name, a, b, tol=0.02):
    inter = a.intersect(b)
    if inter is None:
        v = 0.0
    elif isinstance(inter, (list, tuple)) or hasattr(inter, "__iter__"):
        v = sum(x.volume for x in inter) / 1000.0
    else:
        v = inter.volume / 1000.0
    flag = "OK  " if v <= tol else "!!! "
    print("%s %-26s overlap = %8.3f cm3" % (flag, name, v))
    return v


def main():
    f = M.make_front()
    bk = M.make_back()
    h = M.make_horn()
    e = M.make_eye()
    bt = M.make_button()
    brd = proxy_board()
    bat = battery()

    print("board proxy bbox:", brd.bounding_box())
    print("front bbox      :", f.bounding_box())
    print()
    bad = 0
    bad += report("front x board", f, brd)
    bad += report("back  x board", bk, brd)
    bad += report("front x battery", f, bat)
    bad += report("back  x battery", bk, bat)
    bad += report("front x back", f, bk)
    bad += report("horn  x back", h, bk)
    bad += report("horn  x front", h, f)
    bad += report("eye   x front", e.moved(Pos(-K.EYE_XC, K.EYE_YC, 0.32)), f)
    bad += report("button x front", bt.moved(Pos(*K.BTN_USER_C, 0)), f)
    print("\n合计异常: %.3f cm3" % bad)


if __name__ == "__main__":
    main()
