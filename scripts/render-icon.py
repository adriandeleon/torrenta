"""Rasterises branding/torrenta-icon.svg to a PNG.

The geometry is repeated here by hand because ImageMagick's built-in SVG renderer drops the
gradient fill and the stroked paths, leaving a bare tile. 4x supersampled, Lanczos downscale.

    python3 scripts/render-icon.py branding/torrenta.png
"""
import sys
from PIL import Image, ImageDraw

S = 4
W = 512 * S
out = sys.argv[1]

def px(v): return v * S

img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([px(16), px(16), px(496), px(496)], radius=px(108), fill="#1B1E2A")

# Tray: periwinkle stroke 34, round caps/joins. Approximated with lines + circles + arcs.
peri = "#9AA2D4"; sw = px(34); r = sw // 2
def line(p, q, fill):
    d.line([p, q], fill=fill, width=sw)
    for c in (p, q):
        d.ellipse([c[0]-r, c[1]-r, c[0]+r, c[1]+r], fill=fill)
tray = Image.new("L", (W, W), 0); td = ImageDraw.Draw(tray)
def tline(p, q):
    td.line([p, q], fill=255, width=sw)
    for c in (p, q):
        td.ellipse([c[0]-r, c[1]-r, c[0]+r, c[1]+r], fill=255)
# left wall, bottom, right wall; corners via quarter circles of radius 26 along the path centre
tline((px(146), px(302)), (px(146), px(348)))
tline((px(172), px(374)), (px(340), px(374)))
tline((px(366), px(302)), (px(366), px(348)))
# corner arcs: centre (172,348) r26 from 90..180 deg; centre (340,348) r26 from 0..90
for cx, start, end in ((172, 90, 180), (340, 0, 90)):
    td.arc([px(cx-26)-r, px(348-26)-r, px(cx+26)+r, px(348+26)+r], start, end, fill=255, width=sw)
img.paste(Image.new("RGBA", (W, W), peri), (0, 0), tray)

# Arrow: shaft + head share one vertical gradient (y 128..318).
mask = Image.new("L", (W, W), 0); md = ImageDraw.Draw(mask)
md.rounded_rectangle([px(232), px(128), px(280), px(278)], radius=px(14), fill=255)
hw = px(46); hr = hw // 2
for p, q in (((px(186), px(232)), (px(256), px(304))), ((px(256), px(304)), (px(326), px(232)))):
    md.line([p, q], fill=255, width=hw)
    for c in (p, q):
        md.ellipse([c[0]-hr, c[1]-hr, c[0]+hr, c[1]+hr], fill=255)
grad = Image.new("RGBA", (W, W))
gd = ImageDraw.Draw(grad)
top = (0x5E, 0xEA, 0xD4); bot = (0x22, 0xB8, 0xA6)
y0, y1 = px(128), px(318)
for y in range(W):
    t = min(1, max(0, (y - y0) / (y1 - y0)))
    c = tuple(round(top[i] + (bot[i] - top[i]) * t) for i in range(3)) + (255,)
    gd.line([(0, y), (W, y)], fill=c)
img.paste(grad, (0, 0), mask)

img = img.resize((512, 512), Image.LANCZOS)
img.save(out)
print("wrote", out)
