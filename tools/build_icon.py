"""Four Drop icon (design-package appIcon): an amber disc (#E0A040, unused by any other factory app), a deep-brown
(#4A2E14, the same hue family) three-column rack silhouette with round holes, and one cream disc falling into the
top-right with two speed lines. No text.

  python -I tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same shapes).
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
AMBER = (0xE0, 0xA0, 0x40)
BROWN = (0x4A, 0x2E, 0x14)
CREAM = (0xF6, 0xEB, 0xCF)

# 108-unit adaptive canvas; the safe zone is the central 66 units
RACK = (31.0, 42.0, 77.0, 82.0)            # x0, y0, x1, y1 of the rack face
FEET = [(28.0, 82.0, 36.0, 86.0), (72.0, 82.0, 80.0, 86.0)]
COLS, ROWS = 3, 3
HOLE_R = 5.6
DISC = (62.0, 28.5, 7.2)                    # falling disc centre x, y, radius
SPEED = [((62.0, 15.5), (62.0, 20.5)), ((55.0, 18.5), (55.0, 23.0))]


def holes():
    x0, y0, x1, y1 = RACK
    cw = (x1 - x0) / COLS; rh = (y1 - y0) / ROWS
    return [(x0 + (c + 0.5) * cw, y0 + (r + 0.5) * rh) for c in range(COLS) for r in range(ROWS)]


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=AMBER + (255,)))


def layer_rack():
    def d(dr, k):
        x0, y0, x1, y1 = RACK
        dr.rounded_rectangle((x0 * k, y0 * k, x1 * k, y1 * k), radius=2.5 * k, fill=BROWN + (255,))
        for f in FEET:
            dr.rectangle(tuple(v * k for v in f), fill=BROWN + (255,))
        for cx, cy in holes():
            dr.ellipse(((cx - HOLE_R) * k, (cy - HOLE_R) * k, (cx + HOLE_R) * k, (cy + HOLE_R) * k), fill=(0, 0, 0, 0))
    return big(d)


def layer_disc():
    def d(dr, k):
        cx, cy, r = DISC
        dr.ellipse(((cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k), fill=CREAM + (255,))
        dr.ellipse(((cx - r * 0.6) * k, (cy - r * 0.6) * k, (cx + r * 0.6) * k, (cy + r * 0.6) * k), outline=BROWN + (90,), width=int(0.9 * k))
        for (a, b) in SPEED:
            dr.line((a[0] * k, a[1] * k, b[0] * k, b[1] * k), fill=CREAM + (255,), width=int(2.2 * k))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def circle(cx, cy, r):
    return "M%.2f,%.2fa%.2f,%.2f 0 1,0 %.2f,0a%.2f,%.2f 0 1,0 %.2f,0Z" % (cx - r, cy, r, r, 2 * r, r, r, -2 * r)


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    x0, y0, x1, y1 = RACK
    rack = "M%.1f,%.1fL%.1f,%.1fL%.1f,%.1fL%.1f,%.1fZ" % (x0, y0, x1, y0, x1, y1, x0, y1)
    rack += "".join("M%.1f,%.1fL%.1f,%.1fL%.1f,%.1fL%.1f,%.1fZ" % (f[0], f[1], f[2], f[1], f[2], f[3], f[0], f[3]) for f in FEET)
    rack += "".join(circle(cx, cy, HOLE_R) for cx, cy in holes())
    cx, cy, r = DISC
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Four Drop launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:fillType="evenOdd" android:pathData="%s" />' % (hexc(BROWN), rack),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(CREAM), circle(cx, cy, r)),
        "".join('    <path android:strokeColor="%s" android:strokeWidth="2.2" android:strokeLineCap="round" android:pathData="M%.1f,%.1fL%.1f,%.1f" />\n' % (hexc(CREAM), a[0], a[1], b[0], b[1]) for a, b in SPEED).rstrip("\n"),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_rack(), layer_disc()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    os.makedirs(os.path.join(RES, "mipmap-xxxhdpi"), exist_ok=True)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
