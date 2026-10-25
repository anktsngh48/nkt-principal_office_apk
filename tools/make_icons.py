#!/usr/bin/env python3
"""Regenerates every launcher icon and the splash image from tools/logo.png.
Run from the android/ folder:  python3 tools/make_icons.py   (needs Pillow)"""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
RES = HERE.parent / 'app' / 'src' / 'main' / 'res'
logo = Image.open(HERE / 'logo.png').convert('RGBA')
logo = logo.crop(logo.getchannel('A').point(lambda a: 255 if a > 8 else 0).getbbox())  # trim the empty margin
side = max(logo.size)
sq = Image.new('RGBA', (side, side), (0, 0, 0, 0))
sq.paste(logo, ((side - logo.width) // 2, (side - logo.height) // 2), logo)
logo = sq

def canvas(px, art_frac, bg=None):
    """Square canvas of px with the logo centred, art_frac = share of the canvas it fills."""
    im = Image.new('RGBA', (px, px), bg or (0, 0, 0, 0))
    a = max(1, round(px * art_frac))
    art = logo.resize((a, a), Image.LANCZOS)
    im.alpha_composite(art, ((px - a) // 2, (px - a) // 2))
    return im

dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for name, k in dens.items():
    d = RES / f'mipmap-{name}'
    d.mkdir(parents=True, exist_ok=True)
    # Adaptive icon foreground: 108dp canvas, artwork inside the 66dp safe zone (61%).
    canvas(round(108 * k), 0.60).save(d / 'ic_launcher_foreground.png', optimize=True)
    # Legacy icon (Android 7): 48dp, white rounded tile with the logo on it.
    px = round(48 * k)
    tile = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    from PIL import ImageDraw
    m = Image.new('L', (px * 4, px * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, px * 4 - 1, px * 4 - 1), radius=px * 4 * 0.22, fill=255)
    m = m.resize((px, px), Image.LANCZOS)
    tile.paste((255, 255, 255, 255), (0, 0), m)
    tile.alpha_composite(canvas(px, 0.86))
    tile.save(d / 'ic_launcher.png', optimize=True)
    rnd = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    c = Image.new('L', (px * 4, px * 4), 0)
    ImageDraw.Draw(c).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
    c = c.resize((px, px), Image.LANCZOS)
    rnd.paste((255, 255, 255, 255), (0, 0), c)
    rnd.alpha_composite(canvas(px, 0.70))
    rnd.save(d / 'ic_launcher_round.png', optimize=True)

# Splash: 288dp canvas @xxxhdpi. Android 12+ shows only the central 192dp circle, so the
# logo is 152dp (its rounded corners stay inside that circle).
sp = RES / 'drawable-xxxhdpi'
sp.mkdir(parents=True, exist_ok=True)
canvas(1152, 152 / 288).save(sp / 'splash_logo.png', optimize=True)
# Logo used on the offline screen.
canvas(512, 1.0).save(sp / 'logo.png', optimize=True)
print('icons written to', RES)
