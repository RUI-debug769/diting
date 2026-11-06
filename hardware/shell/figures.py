"""交付插图：正视 / 后视 / 等轴测 / 爆炸图。

用法：  /tmp/cadenv/bin/python figures.py
输出：  preview/fig1_front.png … fig5_section.png
"""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager as fm
from matplotlib.collections import PolyCollection

for _f in ("/home/murdo_klein/.local/share/fonts/NotoSansSC-Regular.otf",
           "/home/murdo_klein/.local/share/fonts/NotoSansSC-Bold.otf"):
    try:
        fm.fontManager.addfont(_f)
    except Exception:
        pass
plt.rcParams["font.family"] = ["Noto Sans SC", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

from build123d import Pos, Rot, Cylinder, Align

import params as K
import model as M
import validate as V
from preview import Part, section

OUT = "preview/"
DPI = 110
BODY = (0.63, 0.55, 0.44)
BACKC = (0.55, 0.48, 0.38)
HORN = (0.87, 0.47, 0.33)
EYE = (0.97, 0.94, 0.87)
PCB = (0.09, 0.30, 0.19)
BAT = (0.18, 0.33, 0.70)
BTN = (0.22, 0.18, 0.15)


def rot_xyz(ax, ay, az):
    cx, sx = np.cos(ax), np.sin(ax)
    cy, sy = np.cos(ay), np.sin(ay)
    cz, sz = np.cos(az), np.sin(az)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def render(parts, R, fname, title, figsize=(7.2, 7.2), margin=0.04):
    polys, cols, deps = [], [], []
    light = np.array([0.4, 0.45, 0.8])
    light = light / np.linalg.norm(light)
    cam = np.array([0.0, -1.0, 0.0])   # 相机在 -Y' 方向看
    for p in parts:
        v = p.v @ R.T
        F = p.f
        a = np.cross(v[F[:, 1]] - v[F[:, 0]], v[F[:, 2]] - v[F[:, 0]])
        nn = a / (np.linalg.norm(a, axis=1, keepdims=True) + 1e-12)
        vis = nn @ cam < 0
        F, nn = F[vis], nn[vis]
        if len(F) == 0:
            continue
        sh = np.clip(np.abs(nn @ light), 0, 1) * 0.62 + 0.38
        c = np.clip(np.array(p.color)[None, :] * sh[:, None], 0, 1)
        u = v @ np.array([1.0, 0, 0])
        w = -v @ np.array([0, 0, 1.0])
        quads = np.stack([u[F[:, 0]], w[F[:, 0]], u[F[:, 1]], w[F[:, 1]],
                          u[F[:, 2]], w[F[:, 2]]], axis=1).reshape(-1, 3, 2)
        polys.append(quads)
        cols.append(c)
        deps.append(v[F].mean(axis=1) @ np.array([0.0, 1.0, 0.0]))
    polys = np.vstack(polys)
    cols = np.vstack(cols)
    deps = np.concatenate(deps)
    order = np.argsort(-deps)
    fig, ax = plt.subplots(figsize=figsize, dpi=DPI)
    ax.add_collection(PolyCollection(polys[order], facecolors=cols[order],
                                     edgecolors="none"))
    ax.autoscale_view()
    ax.set_aspect("equal")
    ax.set_axis_off()
    ax.set_title(title, fontsize=11)
    fig.tight_layout(pad=0.2)
    fig.savefig(OUT + fname, facecolor="white")
    plt.close(fig)
    print("  ->", fname)


def board_proxy():
    out = []
    for xs0, xs1, ys0, ys1, zs0, zs1, name in V.PARTS:
        out.append(M.bbox(xs0 - K.BOARD_XC, ys0 + K.BOARD_Y0, K.BOARD_Z0 - zs1,
                          xs1 - K.BOARD_XC, ys1 + K.BOARD_Y0, K.BOARD_Z0 - zs0))
    return out


def main():
    f, b, h = M.make_front(), M.make_back(), M.make_horn()
    e, bt = M.make_eye(), M.make_button()
    eyes = [Part(e.moved(Pos(-K.EYE_XC, K.EYE_YC, 0.30)), EYE),
            Part(e.moved(Pos(K.EYE_XC, K.EYE_YC, 0.30)), EYE)]
    btns = [Part(bt.moved(Pos(*K.BTN_USER_C, 0.0)), BTN),
            Part(bt.moved(Pos(*K.BTN_RST_C, 0.0)), BTN)]

    R_iso = rot_xyz(np.radians(-62), 0, np.radians(-28))
    R_iso2 = rot_xyz(np.radians(-58), 0, np.radians(28))

    common = [Part(f, BODY), Part(b, BACKC), Part(h, HORN)] + eyes + btns
    print("fig1 front")
    render(common, rot_xyz(np.radians(-90), 0, 0), "fig1_front.png", "图1 正视图（像素面）")

    print("fig2 back")
    Ry = rot_xyz(0, np.pi, 0)
    backp = []
    for p in common:
        q = Part.__new__(Part)
        q.v = p.v @ Ry.T
        q.f, q.color, q.alpha = p.f, p.color, p.alpha
        backp.append(q)
    render(backp, rot_xyz(np.radians(-90), 0, 0), "fig2_back.png", "图2 后视图")

    print("fig3 iso")
    render(common, R_iso, "fig3_iso.png", "图3 等轴测（前）", figsize=(7.6, 7.6))

    print("fig4 exploded")
    dz = [("back", 96.0), ("bat", 64.0), ("board", 30.0)]
    ex = [Part(b.moved(Pos(0, 0, 96.0)), BACKC)]
    ex.append(Part(Cylinder(K.BAT_D / 2, K.BAT_L).moved(
        Pos(K.BAT_CX, K.BAT_CY, K.BAT_CZ + 64.0)), BAT))
    for bp in board_proxy():
        ex.append(Part(bp.moved(Pos(0, 0, 30.0)), PCB))
    ex += [Part(f, BODY), Part(h, HORN)] + eyes + btns
    render(ex, R_iso, "fig4_exploded.png", "图4 爆炸图：后盖 / 18650 / 主板 / 前壳", figsize=(8.4, 8.4))

if __name__ == "__main__":
    main()
