"""渲染预览：正交三视图 + 等轴测 + 剖切（painter's algorithm，不依赖 3D 后端）。"""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.collections import PolyCollection
from build123d import Plane, Pos

import model as M
import params as K

OUT = "preview/"
DPI = 120


def tris(shape, tol=0.45):
    verts, faces = shape.tessellate(tol, 0.5)
    v = np.array([[p.X, p.Y, p.Z] for p in verts], float)
    return v, np.array(faces)


class Part:
    def __init__(self, shape, color, tol=0.45, alpha=1.0):
        self.v, self.f = tris(shape, tol)
        self.color = np.array(color)
        self.alpha = alpha


def render(parts, u=0, v=1, d=2, flip_u=False, flip_v=False,
           fname="view.png", title="", margin=0.02):
    """u/v/d = 视空间坐标轴索引；d 大者先画（相机在 -d 方向）。"""
    allv = np.vstack([p.v for p in parts])
    uu, vv = allv[:, u].copy(), allv[:, v].copy()
    if flip_u:
        uu = -uu
    if flip_v:
        vv = -vv
    u0, u1 = uu.min(), uu.max()
    v0, v1 = vv.min(), vv.max()
    w, h = u1 - u0, v1 - v0
    fig, ax = plt.subplots(figsize=((w + 30) / 25.4, (h + 30) / 25.4), dpi=DPI)
    light = np.array([0.3, 0.4, 0.87])
    light /= np.linalg.norm(light)
    polys, cols, depth = [], [], []
    for p in parts:
        pv = p.v.copy()
        pu, pv2, pd = pv[:, u].copy(), pv[:, v].copy(), pv[:, d].copy()
        if flip_u:
            pu = -pu
        if flip_v:
            pv2 = -pv2
        F = p.f
        a = np.cross(pv[F[:, 1]] - pv[F[:, 0]], pv[F[:, 2]] - pv[F[:, 0]])
        n = a / (np.linalg.norm(a, axis=1, keepdims=True) + 1e-12)
        front = n[:, d] < 0          # 面向相机（相机在 -d 侧）
        F = F[front]
        nn = n[front]
        if len(F) == 0:
            continue
        sh = np.clip(np.abs(nn @ light), 0, 1) * 0.65 + 0.35
        c = np.clip(p.color[None, :] * sh[:, None], 0, 1)
        quads = np.stack([pu[F[:, 0]], pv2[F[:, 0]],
                          pu[F[:, 1]], pv2[F[:, 1]],
                          pu[F[:, 2]], pv2[F[:, 2]]], axis=1).reshape(-1, 3, 2)
        polys.append(quads)
        cols.append(c)
        depth.append(pd[F].mean(axis=1))
    polys = np.vstack(polys)
    cols = np.vstack(cols)
    depth = np.concatenate(depth)
    order = np.argsort(-depth)
    ax.add_collection(PolyCollection(polys[order], facecolors=cols[order],
                                     edgecolors="none", linewidths=0))
    ax.set_xlim(u0 - w * margin, u1 + w * margin)
    ax.set_ylim(v0 - h * margin, v1 + h * margin)
    ax.set_aspect("equal")
    ax.set_axis_off()
    ax.set_title(title, fontsize=10)
    fig.tight_layout(pad=0.3)
    fig.savefig(OUT + fname, facecolor="white")
    plt.close(fig)
    print("  ->", fname)


def section(shape, offset, plane="xy"):
    pl = {"xy": Plane.XY.offset(offset), "xz": Plane.XZ.offset(offset),
          "yz": Plane.YZ.offset(offset)}[plane]
    sec = shape.intersect(pl)
    if sec is None:
        return []
    idx = {"xy": (0, 1), "xz": (0, 2), "yz": (1, 2)}[plane]
    out = []
    for fc in sec.faces():
        for w in fc.wires():
            out.append([(p.X, p.Y, p.Z) for p in w.vertices()])
    out2 = []
    for poly in out:
        pts = [w.vertices() for w in []]
    # 用离散化顶点，保留平面内的两个坐标
    out3 = []
    for fc in sec.faces():
        for w in fc.wires():
            pts = []
            for e in w.edges():
                for t in (0.0, 0.25, 0.5, 0.75):
                    pnt = e.position_at(t)
                    pts.append((pnt.X, pnt.Y, pnt.Z))
            out3.append([(p[idx[0]], p[idx[1]]) for p in pts])
    return out3


def main():
    f = M.make_front()
    b = M.make_back()
    h = M.make_horn()
    e = M.make_eye()
    bt = M.make_button()

    body_col = (0.60, 0.52, 0.42)
    dark = (0.47, 0.41, 0.33)
    horn_col = (0.85, 0.46, 0.33)
    eye_col = (0.96, 0.93, 0.86)
    lbl = (0.20, 0.10, 0.06)
    parts = [Part(f, body_col), Part(b, dark), Part(h, horn_col),
             Part(e.moved(Pos(-K.EYE_XC, K.EYE_YC, 0.35)), eye_col, 0.3),
             Part(e.moved(Pos(K.EYE_XC, K.EYE_YC, 0.35)), eye_col, 0.3),
             Part(bt.moved(Pos(*K.BTN_USER_C, 0.0)), lbl, 0.3),
             Part(bt.moved(Pos(*K.BTN_RST_C, 0.0)), lbl, 0.3)]

    print("rendering views...")
    render(parts, u=0, v=1, d=2, fname="view_front.png",
           title="FRONT - pixel face (+Z toward viewer)")
    render(parts, u=0, v=1, d=2, flip_u=True, fname="view_back.png", title="BACK (X mirrored)")

    # 等轴测：绕坐标轴手工投影
    def iso(parts, fname, title):
        c = np.array([0.577, 0.577, 0.577])
        x = np.array([0.707, -0.707, 0.0])
        y = np.cross(c, x)
        polys, cols, dep = [], [], []
        light = np.array([0.35, 0.5, 0.8])
        light /= np.linalg.norm(light)
        for p in parts:
            F = p.f
            a = np.cross(p.v[F[:, 1]] - p.v[F[:, 0]], p.v[F[:, 2]] - p.v[F[:, 0]])
            n = a / (np.linalg.norm(a, axis=1, keepdims=True) + 1e-12)
            front = n @ c < 0
            F = F[front]
            nn = n[front]
            if len(F) == 0:
                continue
            sh = np.clip(np.abs(nn @ light), 0, 1) * 0.65 + 0.35
            pc = np.clip(p.color[None, :] * sh[:, None], 0, 1)
            pu = p.v @ x
            pv = p.v @ y
            quads = np.stack([pu[F[:, 0]], pv[F[:, 0]], pu[F[:, 1]], pv[F[:, 1]],
                              pu[F[:, 2]], pv[F[:, 2]]], axis=1).reshape(-1, 3, 2)
            polys.append(quads)
            cols.append(pc)
            dep.append((p.v[F].mean(axis=1)) @ c)
        polys = np.vstack(polys)
        cols = np.vstack(cols)
        dep = np.concatenate(dep)
        order = np.argsort(-dep)
        fig, ax = plt.subplots(figsize=(7.5, 7.5), dpi=DPI)
        ax.add_collection(PolyCollection(polys[order], facecolors=cols[order],
                                         edgecolors="none"))
        ax.autoscale_view()
        ax.set_aspect("equal")
        ax.set_axis_off()
        ax.set_title(title, fontsize=10)
        fig.tight_layout(pad=0.2)
        fig.savefig(OUT + fname, facecolor="white")
        plt.close(fig)
        print("  ->", fname)

    iso(parts, "view_iso.png", "ISOMETRIC")

    # 剖切：z 平面看内部
    fig, axs = plt.subplots(1, 3, figsize=(19, 6.4), dpi=110)
    for ax, z in zip(axs, (6.0, 12.0, 16.0)):
        pf = section(f, z)
        pb = section(b, z)
        ax.add_collection(PolyCollection(pf, facecolors="#d8c3a0", edgecolors="k", linewidths=0.5))
        ax.add_collection(PolyCollection(pb, facecolors="#a08a68", edgecolors="k", linewidths=0.5))
        ax.set_aspect("equal")
        ax.set_title("horizontal cut z=%.1f" % z)
        ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(OUT + "cuts_z.png", facecolor="white")
    plt.close(fig)
    print("  -> cuts_z.png")

    fig, axs = plt.subplots(1, 2, figsize=(17, 6.4), dpi=110)
    for ax, off in zip(axs, (45.0, 20.0)):
        for sh, col in ((f, "#d8c3a0"), (b, "#a08a68")):
            for poly in section(sh, off, "yz"):
                ax.add_patch(plt.Polygon(poly, closed=True, fc=col, ec="k", lw=0.6))
        ax.set_aspect("equal")
        ax.set_title("vertical cut Y=%.1f (Y-Z plane)" % off)
        ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(OUT + "cuts_y.png", facecolor="white")
    plt.close(fig)
    print("  -> cuts_y.png")

    # 侧视（X 投影）
    render(parts, u=1, v=2, d=0, fname="view_side.png", title="SIDE  (X projection, Y-Z)")

    print("done")


if __name__ == "__main__":
    main()
