"""Contrast table (D13, ui-audit): every text colour x its background (WCAG ratio) and the 3D colour pairs (CIE L*)."""


def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def Y(h):
    r, g, b = (lin(v) for v in hexrgb(h))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    ya, yb = sorted((Y(a), Y(b)), reverse=True)
    return (ya + 0.05) / (yb + 0.05)


def L(h):
    y = Y(h)
    return 116 * (y ** (1 / 3) if y > 0.008856 else 7.787 * y + 16 / 116) - 16


GLASS = "#B4B3B1"  # darkest measured window glass (royal-ur / factory measure)
pairs = [
    ("正文 Ink / 窗口玻璃", "#1B1F23", GLASS, 4.5), ("次要字 InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("回合牌大字 Ink / CardFace", "#1B1F23", "#FBF6EC", 4.5), ("回合牌状态行 InkSecondary / CardFace", "#3A3F44", "#FBF6EC", 4.5),
    ("选中对手行 Ink / PrimaryContainer", "#1B1F23", "#F6E3C2", 4.5), ("选中对手说明 InkSecondary / PrimaryContainer", "#3A3F44", "#F6E3C2", 4.5),
    ("主按钮字 白 / Primary（悔一步、再来一局、知道了）", "#FFFFFF", "#8A5414", 4.5), ("静音开着 白 / Primary", "#FFFFFF", "#8A5414", 4.5),
    ("次按钮字 Ink / fillSecondary 近似 #E4E2DE", "#1B1F23", "#E4E2DE", 4.5),
    ("危险键字 白 / Error（倒掉、退出）", "#FFFFFF", "#B3261E", 4.5), ("规则页序号 白 / Primary", "#FFFFFF", "#8A5414", 4.5),
    ("设置行 Ink / CardFace", "#1B1F23", "#FBF6EC", 4.5), ("关于行 InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("席位牌 墨 / 牌面", "#2A1E14", "#F4E9D2", 4.5), ("席位牌提醒 红 / 牌面", "#B3261E", "#F4E9D2", 4.5),
    ("计数牌 红子数 / 牌面", "#A83222", "#F4E9D2", 3.0), ("计数牌 黄子数 / 牌面", "#7A5A10", "#F4E9D2", 3.0), ("计数牌胜场 墨 / 牌面", "#2A1E14", "#F4E9D2", 4.5),
    ("列号 墨 / 列口牌（常态底）", "#33261A", "#EDD9B8", 3.0), ("列号 墨 / 列口牌（满列红）", "#33261A", "#F57A6B", 3.0),
    ("回合牌红子 / CardFace（图形）", "#C8412E", "#FBF6EC", 3.0), ("回合牌黄子描边 / CardFace（图形）", "#9C7A2A", "#FBF6EC", 3.0),
]
print("| 文字 | 前景 | 底色 | 对比度 | 门槛 | 结果 |")
print("|---|---|---|---|---|---|")
for n, f, b, t in pairs:
    r = ratio(f, b)
    print(f"| {n} | {f} | {b} | {r:.2f}:1 | {t} | {'过' if r >= t else '不过'} |")
print()
lp = [("红子 vs 橡木板", "#C8412E", "#C49C68", 15), ("黄子 vs 橡木板（设计包 needsCheck：分不清就调深一档）", "#EBD9A8", "#C49C68", 15), ("红子 vs 黄子", "#C8412E", "#EBD9A8", 25),
      ("黄子 vs 孔后透视（暗桌面 #4A3A2A）", "#EBD9A8", "#4A3A2A", 25), ("红子 vs 孔后透视", "#C8412E", "#4A3A2A", 10),
      ("落点圈 青白 vs 橡木板", "#DFF6FF", "#C49C68", 10), ("落点圈 橙 vs 橡木板", "#C8500E", "#C49C68", 15), ("光条 vs 红子", "#FFF1C2", "#C8412E", 25)]
print("| 3D 对比 | A | B | 亮度比 | ΔL* | 门槛 ΔL* |")
print("|---|---|---|---|---|---|")
for n, a, b, t in lp:
    print(f"| {n} | {a} | {b} | {ratio(a, b):.2f}:1 | {abs(L(a) - L(b)):.1f} | {'≥ %d' % t if t else '—'} |")
