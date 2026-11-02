# Ritar Trace-ikonerna i samma stil som Direct, Alif och USB (kräver Pillow och numpy).
#   python3 scripts/make-icons.py
# Mörk petroleumgrön bakgrund, guldring med sken, ett T i Cinzel som en mast med signalvågor,
# en mint-triangel och en ljusguld punkt på ringen.
import math, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.join(os.path.dirname(__file__), '..')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
CINZEL = os.path.join(RES, 'font', 'cinzel_bold.ttf')
GOLD, GOLD_DIM, GOLD_PALE, MINT, BG, OUTLINE = (232, 176, 74), (138, 109, 46), (243, 217, 160), (95, 196, 180), (11, 20, 22), (15, 26, 28)
S = 4  # översampling
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

def background(size):
    y, x = np.mgrid[0:size, 0:size]
    d = np.sqrt((x - size / 2) ** 2 + (y - size * 0.45) ** 2) / (size * 0.65)
    c0, c1, c2 = np.array([29, 74, 80]), np.array([17, 37, 40]), np.array(BG)
    t = np.clip(d, 0, 1)[..., None]
    col = np.where(t < 0.62, c0 + (c1 - c0) * (t / 0.62), c1 + (c2 - c1) * ((t - 0.62) / 0.38))
    img = Image.fromarray(col.astype('uint8'), 'RGB').convert('RGBA')
    halo = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    r = size * 0.43
    hd.ellipse([size / 2 - r, size / 2 - r, size / 2 + r, size / 2 + r], outline=GOLD + (70,), width=int(size * 0.05))
    return Image.alpha_composite(img, halo.filter(ImageFilter.GaussianBlur(size * 0.03)))

def arc(d, c, r, a0, a1, color, w):
    d.arc([c[0] - r, c[1] - r, c[0] + r, c[1] + r], a0, a1, fill=color, width=w)

def dashed_circle(d, cx, cy, r, color, w, n=28, fill=0.55):
    for i in range(n):
        a0 = 360 * i / n
        arc(d, (cx, cy), r, a0, a0 + 360 / n * fill, color, w)

def mark(size, k, mono=False):
    n = size * S
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = n / 2
    u = S * k * size / 1024           # en enhet i 1024-ikonen
    R = 300 * u
    g = (255, 255, 255, 255) if mono else GOLD + (255,)
    gd = (255, 255, 255, 140) if mono else GOLD_DIM + (255,)
    gp = (255, 255, 255, 220) if mono else GOLD_PALE + (255,)

    # Ring och en streckad inre cirkel, som en radar
    d.ellipse([c - R, c - R, c + R, c + R], outline=g, width=int(34 * u))
    dashed_circle(d, c, c, R * 0.74, gd, int(9 * u))

    # T i Cinzel, som en mast, med en sändande punkt i toppen
    font = ImageFont.truetype(CINZEL, int(400 * u))
    d.text((c, c + 62 * u), 'T', font=font, fill=g, anchor='mm')
    tip = (c, c - 128 * u)
    tr = 17 * u
    d.ellipse([tip[0] - tr, tip[1] - tr, tip[0] + tr, tip[1] + tr], fill=gp)
    for r, col in ((48 * u, gp), (84 * u, gd)):
        arc(d, tip, r, 145, 215, col, int(14 * u))
        arc(d, tip, r, -35, 35, col, int(14 * u))

    # Accenter på ringen: mint-triangel uppe till vänster, ljusguld punkt nere till höger
    o = R * math.cos(math.pi / 4)
    ar = 44 * u
    tx, ty = c - o, c - o
    tri = [(tx, ty - ar), (tx + ar * 0.95, ty + ar * 0.7), (tx - ar * 0.95, ty + ar * 0.7)]
    if not mono:
        big = [(tx, ty - ar * 1.55), (tx + ar * 1.45, ty + ar * 1.05), (tx - ar * 1.45, ty + ar * 1.05)]
        d.polygon(big, fill=OUTLINE + (255,))
    d.polygon(tri, fill=(255, 255, 255, 255) if mono else MINT + (255,))
    px, py = c + o, c + o
    if not mono:
        d.ellipse([px - ar * 1.25, py - ar * 1.25, px + ar * 1.25, py + ar * 1.25], fill=OUTLINE + (255,))
    d.ellipse([px - ar * 0.8, py - ar * 0.8, px + ar * 0.8, py + ar * 0.8], fill=(255, 255, 255, 255) if mono else GOLD_PALE + (255,))
    return img.resize((size, size), Image.LANCZOS)

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print('skrev', os.path.relpath(path, ROOT))

# Adaptiv ikon: 108 dp per sida
for name, f in DENSITIES.items():
    px = int(108 * f)
    folder = os.path.join(RES, f'mipmap-{name}')
    save(background(px).convert('RGB'), os.path.join(folder, 'ic_launcher_background.png'))
    save(mark(px, 0.62), os.path.join(folder, 'ic_launcher_foreground.png'))
    save(mark(px, 0.62, mono=True), os.path.join(folder, 'ic_launcher_monochrome.png'))

# Stor ikon för README och förhandsvisning
icon = Image.alpha_composite(background(1024), mark(1024, 1)).convert('RGB')
save(icon, os.path.join(ROOT, 'docs', 'icon.png'))
