# Draws the launcher icon (a play button inside a looping arrow) into res/mipmap-*/ic_launcher.png.
#   python tools/make_icon.py
import math
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
S = 768  # drawn large, then scaled down for smooth edges

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([24, 24, S - 24, S - 24], radius=150, fill=(32, 28, 24, 255))
d.rounded_rectangle([24, 24, S - 24, S - 24], radius=150, outline=(70, 60, 50, 255), width=10)

# The loop: most of a circle, ending in an arrowhead.
c, r, w = S / 2, 228, 46
amber = (240, 163, 61, 255)
d.arc([c - r, c - r, c + r, c + r], start=-60, end=230, fill=amber, width=w)
a = math.radians(-60)
tip = (c + r * math.cos(a), c + r * math.sin(a))
d.polygon([(tip[0] - 64, tip[1] - 30), (tip[0] + 52, tip[1] - 46), (tip[0] + 6, tip[1] + 70)], fill=amber)

# The play button.
d.polygon([(c - 62, c - 100), (c - 62, c + 100), (c + 108, c)], fill=(245, 236, 220, 255))

for name, px in SIZES.items():
    out = os.path.join(RES, "mipmap-" + name)
    os.makedirs(out, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
img.resize((192, 192), Image.LANCZOS).save(os.path.join(HERE, "icon-preview.png"))
print("icons written")
