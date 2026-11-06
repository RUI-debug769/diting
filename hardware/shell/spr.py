"""从像素图提取轮廓，转成 mm 坐标的多边形（shapely）。

像素坐标 (col,row)，col 0..19 向右，row 0..17 向下。
世界坐标：X = (col-10)*P，Y = (17-row-1)*P。
"""
from shapely.geometry import box, Polygon
from shapely.ops import unary_union
from params import P, GRID_W, GRID_H

# 精灵（取自 /tmp/pet2/c5.png 第 1 帧的居中版）：每行像素列区间
BODY_SPANS = {
    4:  [(5, 14)],
    5:  [(3, 16)],
    6:  [(3, 16)],
    7:  [(2, 17)],
    8:  [(2, 17)],
    9:  [(2, 17)],
    10: [(2, 17)],
    11: [(2, 17)],
    12: [(2, 17)],
    13: [(3, 16)],
    14: [(3, 16)],
    15: [(5, 14)],
    16: [(5, 7), (12, 14)],
}

# 耳朵：row2 外角一小撮，row3 三格；再补一格把耳朵焊到肩台上（否则悬空）
EAR_CELLS = [(3, 2), (16, 2), (3, 3), (4, 3), (5, 3), (14, 3), (15, 3), (16, 3)]

# 眼睛（row 9-10, col 6-7 / 12-13）——2×2 像素
EYE_CELLS_L = [(6, 9), (7, 9), (6, 10), (7, 10)]
EYE_CELLS_R = [(12, 9), (13, 9), (12, 10), (13, 10)]


def cell(cx, cy):
    """一个像素格 → 世界坐标方块（正方形，边长 P）。"""
    x0 = (cx - 10) * P
    x1 = (cx - 9) * P
    y1 = (17 - cy) * P
    y0 = (17 - cy - 1) * P
    return box(x0, y0, x1, y1)


def cells_bbox(cells):
    xs, ys = [], []
    for cx, cy in cells:
        b = cell(cx, cy)
        xs += [b.bounds[0], b.bounds[2]]
        ys += [b.bounds[1], b.bounds[3]]
    return min(xs), min(ys), max(xs), max(ys)


def body_outline(round_r=1.2):
    geoms = []
    for row, spans in BODY_SPANS.items():
        for a, b in spans:
            for cx in range(a, b + 1):
                geoms.append(cell(cx, row))
    for cx, cy in EAR_CELLS:
        geoms.append(cell(cx, cy))
    # 把耳朵根部补到肩台顶面（Y=78），消除悬空
    geoms.append(box(-45.5, 78.0, -26.0, 84.5))
    geoms.append(box(26.0, 78.0, 45.5, 84.5))
    u = unary_union(geoms)
    if round_r:
        u = u.buffer(round_r, join_style=1, resolution=6)
        u = u.buffer(-round_r, join_style=1, resolution=6)
    return u


def inset(poly, d):
    out = poly.buffer(-d, join_style=1, resolution=6)
    return out


def to_pts(poly):
    """shapely Polygon → [(x,y), ...]，去掉闭合重复点。"""
    return [(round(x, 4), round(y, 4)) for x, y in list(poly.exterior.coords)[:-1]]


def grid_ascii():
    lines = []
    for row in range(GRID_H):
        s = ""
        for col in range(GRID_W):
            on = any(a <= col <= b for a, b in BODY_SPANS.get(row, []))
            ear = (col, row) in EAR_CELLS
            s += "o" if (on or ear) else "."
        lines.append(s)
    return lines


if __name__ == "__main__":
    b = body_outline()
    print("外轮廓 bbox:", [round(v, 2) for v in b.bounds], "面积 mm2:", round(b.area, 1))
    print("\n".join(grid_ascii()))
