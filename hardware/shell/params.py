"""谛听（T5AI-Core 像素宠物）外壳 —— 全部参数，单位 mm。

坐标系：
  X 向右，Y 向上（Y=0 是脚底），Z 从前脸往后（Z=0 是前脸外表面，Z=D 是后背）。
  像素网格 20×18，一格 P mm，精灵居中：X=(col-10)*P，Y=(17-row)*P。
"""

# ---------- 像素与外形 ----------
P = 6.5                 # 一个像素格 = 6.5 mm
GRID_W, GRID_H = 20, 18
EDGE_ROUND = 2.6        # 前后大圆角（loft 截面内缩量）
WALL = 2.4              # 壁厚
D = 44.0                # 总厚度（前脸到后背）
SPLIT_Z = 22.0          # 分模面：前壳 0..22，后盖 22..44
LAP = 4.0               # 后盖裙边与前壳台阶的搭接高度
LAP_STEP = 1.4          # 前壳搭接台阶：外轮廓内缩量（留出裙边）
LAP_T = 1.2             # 后盖裙边厚度（内表面内缩量 = LAP_STEP + SKIRT_CLR）
SKIRT_CLR = 0.20        # 裙边与台阶的单边间隙
DETENT_H = 0.35         # 卡点凸起高度（干涉量 0.15）
DETENT_Z = SPLIT_Z - 3.2

FACE_RECESS = 0.9       # 前脸整体内凹（凸边 = 像素描边）
FACE_RIM = 4.0          # 前脸留边宽度（相对原始轮廓）
EYE_POCKET = 1.9        # 眼窝深度（从 Z=0 起）
EYE_CLR = 0.28

# ---------- T5AI-Core 板（来自官方 STEP T5AI-Core_V101.step） ----------
# 板件坐标系：Xs 0..28.04（宽，0=咪头侧长边）, Ys 0..67.5（长，0=USB 端）, Zs -1.61..4.8（0=PCB 元件面）
BOARD_XC = 14.02        # Xs 中心
BOARD_Y0 = 8.8          # 板 USB 端在模型 Y 上的位置（顶部给模组天线留出 0.6 的让位）         # Ys=0 对应的模型 Y
BOARD_Z0 = 9.0          # Zs=0（PCB 元件面）对应的模型 Z
BOARD_L = 67.5          # PCB 长
BOARD_W = 28.04         # PCB 宽
BOARD_T = 1.61          # PCB 厚
PIN_TOP = 1.39          # 排针在 Zs 的顶端
PIN_BOT = -10.51        # 排针在 Zs 的底端
PIN_X = ((0.02, 2.52), (25.52, 28.02))   # 两排排针在 Xs 的范围
PIN_Y = (11.66, 67.24)


def mx(xs):
    """板 Xs → 模型 X"""
    return xs - BOARD_XC


def my(ys):
    """板 Ys → 模型 Y"""
    return ys + BOARD_Y0


def mz(zs):
    """板 Zs → 模型 Z（元件面朝前）"""
    return BOARD_Z0 - zs


# 关键器件（模型坐标，已换算）
MIC_C = (mx(7.52), my(39.55))          # 驻极体咪头 Ø6 轴线 → (-6.50, 49.55)
MIC_D = 6.0
MIC_HOLE = 4.6                          # 前脸开孔

BTN_USER_C = (mx(19.44), my(37.32))     # USER / P29 → (5.42, 47.32)
BTN_RST_C = (mx(19.44), my(42.59))      # RST       → (5.42, 52.59)
BTN_HOLE = 3.4

LED_PWR = (mx(14.02), my(35.18), mx(15.62), my(35.98))   # (x0,y0,x1,y1)
LED_CRG = (mx(11.35), my(35.14), mx(12.95), my(35.94))
LED_P9A = (mx(17.18), my(9.59), mx(18.78), my(10.39))
LED_P9B = (mx(19.61), my(9.59), mx(21.21), my(10.39))

USB_X = (mx(7.58) - 0.8, mx(15.43) + 0.8)   # 插头让位（USB-C 座 9.4 宽）
USB_Z = (mz(1.5) - 0.7, mz(-0.5) + 0.7)     # 插头让位（3.4 高）

SW_X = (mx(17.79), mx(26.73))               # 电源拨动开关 (3.77, 12.71)
SW_Z = (mz(3.25), mz(-0.91))                # (5.75, 9.91)
SW_Y = (my(0.09), my(8.04))                 # (10.09, 18.04)

# 板卡固定：两条排针槽（槽口朝后，板背 Z=BOARD_Z0+BOARD_T 落在槽底）
CH_W = PIN_X[1][1] - PIN_X[0][0] + 3.6      # 槽外框总宽
CH_INNER = (PIN_X[0][0] - 1.1, PIN_X[1][1] + 1.1)
CH_FLOOR = BOARD_Z0 + BOARD_T               # 10.61 槽底（板背面）
CH_TOP = SPLIT_Z                             # 槽口
PIL_Y = (14.0, 30.0, 46.0, 62.0)             # 支柱位置

# ---------- 电池 18650 ----------
BAT_D = 18.4
BAT_L = 65.0
BAT_CX = 0.0
BAT_CY = 40.0
BAT_CZ = 31.8            # 后盖内腔 22..41.6，电池 22.6..41.0
BAT_STOP_X = 33.5        # 后盖两端挡筋
BAT_STOP_T = 2.0

# ---------- 眼片 / 芽 ----------
EYE_W = 13.4            # 眼窝边长（像素眼 2×2 格 = 13）
EYE_T = 1.6
EYE_XC = 19.5
EYE_YC = 45.5
EYE_R = 1.0

HORN_Y0 = 84.5          # 芽从头顶长出来
HORN_H = 26.0
HORN_PEG_D = 9.0
HORN_PEG_H = 6.0
HORN_BASE_W = 26.0
HORN_TOP_W = 13.0

# ---------- 板卡托架（前壳内侧） ----------
PIN_TIP_Z = 19.51       # 排针尖端（Zs -10.51）→ 承托面
TRAY_XW = (14.2, 15.2)  # 侧壁 X 外缘 / 内缘（板边 14.02 之外）
TRAY_FLOOR_X = 11.3     # 承托条内缘（排针外缘 11.5 之内）
TRAY_Y = (9.0, 78.5)    # 托架 Y 范围
PAD_BOX = (-6.0, 60.0, 2.4, 6.0, 80.0, 6.0)   # 压住 T5-E1 屏蔽罩的凸台

# ---------- 按键帽 ----------
BTN_CAP_D = 4.5
BTN_POCKET_D = 4.6
BTN_HOLE_D = 3.2
BTN_CAP_T = 1.15
BTN_STEM_D = 3.1
BTN_STEM_L = 4.7

# ---------- 芽的定位销 ----------
HORN_PEG_D = 8.0
HORN_PEG_L = 6.0
HORN_PEG_Z = (15.0, 29.0)     # 两个销：一个进前壳，一个进后盖

PART_NAMES = ("front", "back", "horn", "eye_l", "eye_r", "button")
