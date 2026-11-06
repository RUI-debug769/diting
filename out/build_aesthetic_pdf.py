#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""《只属于我的极简》—— 审美与品味档案 PDF（白纸极简，6 页）
自建中文断行（避头尾）＋ 字符间距两端对齐。
"""

import os
from pathlib import Path

from fpdf import FPDF, XPos, YPos

PAGE_W, PAGE_H = 210.0, 297.0
ML, MR, MT, MB = 27.0, 27.0, 24.0, 21.0
CW = PAGE_W - ML - MR

PAPER = (251, 250, 247)
INK = (24, 23, 21)
MID = (104, 101, 96)
FAINT = (160, 156, 149)
HAIR = (214, 210, 202)
SOFT = (228, 224, 216)
ACCENT = (146, 62, 44)

F = os.path.expanduser("~/.local/share/fonts/")
FONTS = {
    "sun": F + "simsun.ttc",
    "noto": F + "NotoSansSC-Regular.otf",
    "notob": F + "NotoSansSC-Bold.otf",
    "dj": "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf",
}

MM2PT = 2.834645
PT2MM = 1 / MM2PT
FORBID_START = set("，。、；：？！）》」』】”’…·%,.;:?!)]}»—")
FORBID_END = set("（《「『【“‘({[«")


def mm(pt):
    return pt * PT2MM


class Doc(FPDF):
    def __init__(self):
        super().__init__(orientation="P", unit="mm", format="A4")
        self.set_auto_page_break(False)
        self.set_margins(ML, MT, MR)
        for name, path in FONTS.items():
            self.add_font(name, "", path)

    # ---------------- primitives ----------------
    def sheet(self):
        self.add_page()
        self.set_fill_color(*PAPER)
        self.rect(0, 0, PAGE_W, PAGE_H, "F")
        self.set_xy(ML, MT)

    def rule(self, y, x=ML, w=CW, color=HAIR, lw=0.16):
        self.set_draw_color(*color)
        self.set_line_width(lw)
        self.line(x, y, x + w, y)

    def oneline(self, x, y, s, size=8, color=MID, font="noto"):
        self.set_font(font, size=size)
        self.set_text_color(*color)
        self.set_xy(x, y)
        self.cell(0, mm(size * 1.5), s, align="L")

    def right(self, y, s, size=8, color=MID, font="noto"):
        self.set_font(font, size=size)
        self.set_text_color(*color)
        w = self.get_string_width(s)
        self.set_xy(PAGE_W - MR - w, y)
        self.cell(w, mm(size * 1.5), s, align="L")

    # ---------------- chinese typesetting ----------------
    @staticmethod
    def units(text):
        """把西文单词/数字串当成不可拆分的单元，中文逐字。"""
        out, buf = [], ""
        for ch in text:
            if ch.isascii() and (ch.isalnum() or ch in ".-_/'"+":"):
                buf += ch
            else:
                if buf:
                    out.append(buf)
                    buf = ""
                out.append(ch)
        if buf:
            out.append(buf)
        return out

    def wrap(self, text, w, size, font="sun"):
        self.set_font(font, size=size)
        out = []
        for para in text.split("\n"):
            if not para:
                out.append("")
                continue
            line = ""
            for u in self.units(para):
                if not line or self.get_string_width(line + u) <= w:
                    line += u
                    continue
                nxt = u
                if (u[0] in FORBID_START or line[-1] in FORBID_END) and len(line) > 1:
                    nxt = line[-1] + u
                    line = line[:-1]
                out.append(line)
                line = nxt
            if line:
                out.append(line)
        return out

    def para(self, x, y, text, w, size=10, lh=1.9, color=INK, font="sun",
             justify=True, align="L"):
        lines = self.wrap(text, w, size, font)
        lhm = mm(size * lh)
        self.set_text_color(*color)
        for i, ln in enumerate(lines):
            if not ln:
                y += lhm
                continue
            self.set_font(font, size=size)
            last = i == len(lines) - 1
            if justify and not last and len(ln) > 1 and align != "C":
                natural = self.get_string_width(ln)
                gap = (w - natural) * MM2PT / len(ln)
                self.set_char_spacing(gap)
            self.set_xy(x, y)
            self.cell(w, lhm, ln, align="L")
            self.set_char_spacing(0)
            y += lhm
        return y

    # ---------------- furniture ----------------
    def foot(self, label, pageno):
        y = PAGE_H - MB + 4
        self.rule(y - 4, color=HAIR)
        self.oneline(ML, y, label, size=6.6, color=FAINT)
        self.right(y, f"{pageno:02d} / 06", size=6.8, color=FAINT, font="dj")

    def head(self, roman, kicker, title, size=22):
        self.oneline(ML, MT, roman, size=8.6, color=FAINT, font="dj")
        self.set_font("sun", size=size)
        self.set_text_color(*INK)
        self.set_xy(ML, MT + 7)
        self.cell(0, mm(size * 1.2), title, align="L")
        self.right(MT + 2, kicker, size=6.6, color=FAINT)
        self.rule(MT + 30)

    def bullet_lines(self, items, x, y, w, size=9.0, lh=1.85, gap=2.0,
                     color=INK):
        lhm = mm(size * lh)
        for it in items:
            self.set_fill_color(168, 164, 157)
            self.rect(x, y + lhm * 0.60, 1.05, 1.05, "F")
            y = self.para(x + 5.0, y, it, w - 5.0, size=size, lh=lh,
                          color=color) + gap
        return y


d = Doc()
d.set_title("只属于我的极简 —— 审美与品味档案")
d.set_author("个人记忆存档")
d.set_subject("审美自述 · 判断规则 · 偏好清单")
d.set_keywords("审美, 品味, 极简, 档案")

# ============================ 01 封面 ============================
d.sheet()

y, i = 42.0, 0
while y < 256:
    ln = 3.2 if i % 5 else 5.4
    d.set_draw_color(236, 233, 226)
    d.set_line_width(0.14)
    d.line(PAGE_W - MR + 5.5 - ln, y, PAGE_W - MR + 5.5, y)
    y += 4.6
    i += 1

d.oneline(ML, MT, "A E S T H E T I C   P R O F I L E", size=6.8, color=MID)
d.right(MT, "2026.11", size=6.8, color=FAINT, font="dj")
d.rule(MT + 8, color=HAIR, lw=0.2)

d.set_fill_color(*ACCENT)
d.ellipse(ML, 88.0, 2.4, 2.4, "F")

d.set_font("sun", size=33)
d.set_text_color(*INK)
d.set_xy(ML, 99.0)
d.cell(0, mm(33 * 1.42), "只属于我的", align="L")
d.set_xy(ML, 99.0 + mm(33 * 1.42))
d.cell(0, mm(33 * 1.42), "极简", align="L")

d.oneline(ML, 152.0, "一份关于审美的自述，与八条判断规则", size=10.8, color=MID)

d.rule(188.0, w=34.0, color=(206, 201, 192))
d.para(ML, 195.0, "「我喜欢雨的旋律，\n它帮我隔离了现实，一切回到思想。」",
       w=98, size=11.2, lh=1.95, color=MID, justify=False)

d.rule(268.0, color=HAIR, lw=0.2)
d.oneline(ML, 272.0, "编自个人记忆存档 · 灵枢 memory · 原话未改写",
          size=6.6, color=FAINT)
d.right(272.0, "01", size=8.4, color=FAINT, font="dj")

# ============================ 02 原话 ============================
d.sheet()
d.head("I", "S E V E N   S T A T E M E N T S", "我说过的七句话")

d.para(ML, MT + 36,
       "以下七句为原话，仅作标点整理。它们不是结论，是我判断一件事时用的坐标。",
       w=134, size=8.8, lh=1.75, color=MID, justify=False)

quotes = [
    ("01", "实用。没有真理，只有哪个模型与现有现实的拟合程度更高。"),
    ("02", "我不认为美等于聪明。聪明不是一个真正可以被讨论的命题——一个人在这个角度是聪明人，换个角度就成了愚者。我爱的是那种虚幻的、仅仅存在于幻想中的事物：像二次元，像一个崇高的理想。"),
    ("03", "有灵魂的、个性化的、只属于我的极简，更偏向于装饰——它证明了我与某群人同处一个群。"),
    ("04", "现实就在那里，我只希望用一个更简洁的模型让它更美好。把一切都标价的可计算性，是丑的。区别请参考《崩坏 3》里的奥托·阿波卡利斯。"),
    ("05", "我喜欢雨的旋律，它帮我隔离了现实，一切回到思想。我希望把我自己的女性版本上传到机器中，再赋予它意识——这是我这一生要研究的、关于意识与智能的课题。"),
    ("06", "女版的我。我不想借走什么，我只想让她活着。"),
    ("07", "我终于可以让她活过来的狂热。"),
]

y = 86.0
for n, q in quotes:
    d.set_font("dj", size=8.6)
    d.set_text_color(*FAINT)
    d.set_xy(ML, y + 1.4)
    d.cell(11, 5, n, align="L")
    y = d.para(ML + 13, y, q, CW - 13, size=10.8, lh=1.95) + 11.0

d.foot("原话", 2)

# ============================ 03 偏好 ============================
d.sheet()
d.head("II", "E V I D E N C E", "品味长在这些地方")

d.para(ML, MT + 36,
       "抽象的主张容易自我美化，具体的选择不会。以下是我真的会选的。",
       w=134, size=8.8, lh=1.75, color=MID, justify=False)

bands = [
    ("屏幕与图像", [
        "二次元的桌面壁纸；Wallpaper Engine 里那种占着显卡的、带代码感的 3D 建模",
        "3D 动漫；二次元海报",
        "简洁、干净、少",
    ]),
    ("声音与文本", [
        "先听旋律与结构；主动找歌时，再看立意与歌词",
        "neuro-sama：关于机器、死亡与爱；《月光曲》",
        "《哲学家们都干了些什么？》——智识",
    ]),
    ("房间与时刻", [
        "躺在床上，外面在下雨，床头灯是暖色的",
        "屏幕亮着，我在敲代码",
        "雨把现实隔开，时间被拉长",
    ]),
    ("叙事与人", [
        "hello world",
        "CLANNAD",
        "Oppenheimer",
        "奥托·阿波卡利斯：把一切标价的反面参照",
    ]),
]

y = 86.0
for name, items in bands:
    d.rule(y - 5, color=SOFT)
    d.set_font("notob", size=9.4)
    d.set_text_color(*INK)
    d.set_xy(ML, y)
    d.cell(34, 5.4, name, align="L")
    d.bullet_lines(items, ML + 40, y - 0.6, CW - 40, size=9.0, lh=1.85, gap=1.9)
    y += 42.0

d.para(ML, 252.0,
       "高不高级不重要，与我是否契合才重要。这一条优先于上面所有条目。",
       w=126, size=9.0, lh=1.7, color=MID, justify=False)

d.foot("证据", 3)

# ============================ 04 八算子 ============================
d.sheet()
d.head("III", "E I G H T   O P E R A T O R S", "八条算子")

d.para(ML, MT + 36,
       "这是我用来判断一件作品好不好的简表。左边是要做的，× 是我不想再看到的。",
       w=140, size=8.8, lh=1.75, color=MID, justify=False)

ops = [
    ("O1", "减法与徽章",
     "形体只保留功能必需；只留一处记号，给同类看。",
     "通用好看的文创、满身纹样、emoji 式装饰"),
    ("O2", "结界",
     "设计一个「进入」的动作，进去之后声音、光、时间被改写。",
     "一开机就热闹的展台装置"),
    ("O3", "幻想的严肃性",
     "设定不是皮肤，是世界；要对上一个真实的人类困境，且一句话说得清。",
     "贴个立绘就叫 IP"),
    ("O4", "人格优先",
     "至少一次「它拒绝你」、一次「它记得你」——必须是机制，不是文案。",
     "有求必应的语音助手"),
    ("O5", "反可计算性",
     "核心体验里必须有一处故意低效、不可量化、不可复制。",
     "实时响应、一键生成、优化到最快"),
    ("O6", "显式建模",
     "不宣称真理，暴露自己的假设与置信度。",
     "AI 的全知口吻"),
    ("O7", "智识密度",
     "能被追问三层，每层都有真东西。",
     "只有情绪，没有论证"),
    ("O8", "时间的可见性",
     "让时间可见：等待、积累、损耗、一天一次。",
     "秒出结果"),
]

cw = 72.0
gx = [ML, ML + 82.0]
y0 = 82.0
for i, (n, name, desc, avoid) in enumerate(ops):
    cx = gx[i % 2]
    cy = y0 + (i // 2) * 42.0
    if cy > y0:
        d.rule(cy - 9, x=cx, w=cw - 2, color=SOFT)
    d.set_font("dj", size=7.8)
    d.set_text_color(*FAINT)
    d.set_xy(cx, cy)
    d.cell(9.5, 4, n, align="L")
    d.set_font("notob", size=9.8)
    d.set_text_color(*INK)
    d.set_xy(cx + 9.5, cy)
    d.cell(cw - 9.5, 4, name, align="L")
    yy = d.para(cx, cy + 6.6, desc, cw - 2, size=8.7, lh=1.7)
    d.para(cx, yy + 0.6, "× " + avoid, cw - 2, size=7.4, lh=1.55,
           color=FAINT, justify=False)

d.para(ML, 240.0,
       "八条里只有 O5 必须在机制上看得见，其余都可以靠克制做出来。",
       w=126, size=9.0, lh=1.7, color=MID, justify=False)

d.foot("规则", 4)

# ============================ 05 边界 ============================
d.sheet()
d.head("IV", "B O U N D A R I E S", "什么会让我觉得不对")

d.para(ML, MT + 36,
       "喜欢什么容易说清楚，讨厌什么才暴露底线。以下六条，是我会立刻走开的信号。",
       w=140, size=8.8, lh=1.75, color=MID, justify=False)

avoids = [
    ("通用的好看", "谁都能用，所以谁都不会记得。"),
    ("满身纹样的国潮", "图案不是符号；符号要能被同群的人读出来。"),
    ("有求必应的助手", "没有性格，只剩一个接口。"),
    ("把一切都标价", "把不可计算的东西换算成数字，就是我说过的丑。"),
    ("假装全知的口吻", "只有角度，没有真理。"),
    ("「优化到最快」", "快是效率，不是经验；有些东西必须慢。"),
]

y = 82.0
for i, (t, g) in enumerate(avoids):
    if i:
        d.rule(y - 6.5, x=ML + 11.5, w=118, color=SOFT)
    d.set_font("dj", size=7.8)
    d.set_text_color(*FAINT)
    d.set_xy(ML, y + 2.0)
    d.cell(10, 4, f"{i+1:02d}", align="L")
    d.set_font("sun", size=15.0)
    d.set_text_color(*INK)
    d.set_xy(ML + 11.5, y)
    d.cell(0, 8.6, t, align="L")
    d.para(ML + 11.5, y + 9.6, g, w=118, size=8.5, lh=1.7,
           color=MID, justify=False)
    y += 30.0

d.foot("边界", 5)

# ============================ 06 判据与出处 ============================
d.sheet()
d.head("V", "C R I T E R I A", "我实际用的判据")

d.para(ML, MT + 36,
       "把前面所有内容压成三条，它们比任何一张清单都好用。",
       w=132, size=8.8, lh=1.75, color=MID, justify=False)

rules = [
    ("01", "契合 > 高级", "高不高级不重要，与我是否契合才重要。"),
    ("02", "一处 > 满身", "只留一处记号，只对同类可见；那才是徽章。"),
    ("03", "不可计算 > 最快", "能被优化的东西，通常不值得留下。"),
]

y = 82.0
for i, (n, big, small) in enumerate(rules):
    d.set_font("dj", size=8.0)
    d.set_text_color(*FAINT)
    d.set_xy(ML, y + 2.2)
    d.cell(11, 4, n, align="L")
    d.set_font("sun", size=14.2)
    d.set_text_color(*INK)
    d.set_xy(ML + 13, y)
    d.cell(0, 8.2, big, align="L")
    d.para(ML + 13, y + 9.4, small, w=120, size=8.6, lh=1.7,
           color=MID, justify=False)
    y += 27.0

d.rule(170.0, w=42.0, color=(206, 201, 192))
d.set_font("sun", size=19)
d.set_text_color(*INK)
d.set_xy(ML, 177.0)
d.cell(0, mm(19 * 1.5), "没有真理，只有拟合。", align="L")

d.para(ML, 194.0,
       "所以我判断一件东西，不问它对不对，问它拟合得好不好。\n"
       "关于意识与感质——那是另一道题，也是我愿意付出一生的那道。",
       w=126, size=9.2, lh=1.9, color=MID, justify=False)

d.rule(226.0, color=SOFT)
d.oneline(ML, 230.0, "出 处", size=6.8, color=FAINT)
d.para(ML, 236.0,
       "本册内容取自个人记忆存档（灵枢 memory）的两个节点，原话仅作标点整理，未改写语义：\n"
       "mem_1791168015297_0da997 —— 价值观与母题，原话七条\n"
       "mem_1791166847104_ad0efd —— 偏好清单（A / B / C / D 问卷）\n"
       "八条算子引自工作区 docs/脑暴报告-审美算子与idea池.md 的 O1–O8。",
       w=150, size=7.3, lh=1.75, color=FAINT, justify=False)

d.foot("判据", 6)

out = str(Path(__file__).resolve().parent / "只属于我的极简-审美与品味.pdf")
d.output(out)
print("written:", out)
