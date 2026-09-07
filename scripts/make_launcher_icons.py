#!/usr/bin/env python3
"""
Generate every launcher/store icon asset from Blender artwork.

  python3 scripts/make_launcher_icons.py --art ART.png [--symbol SYMBOL.png] [--bg '#0B1220']

  ART     full-bleed square artwork (>= 1024 px). Used for: adaptive FOREGROUND layer
          (scaled so the artwork fills the 108 dp canvas; the launcher mask crops the
          edges), legacy ic_launcher (rounded-square) + ic_launcher_round (circle) PNGs,
          and the Play store icon (512, full square).
  SYMBOL  optional: the icon symbol alone on a transparent background. Used for the
          MONOCHROME (themed-icon) layer; if omitted it is derived from ART by luminance.
  BG      adaptive BACKGROUND colour (solid), default sampled from ART's corners.

Writes into app/src/main/res/ and prints the Play 512 path. Requires Pillow.
"""
import argparse, os, sys
from PIL import Image, ImageDraw, ImageOps

RES = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res')
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

def sq(im):
    w, h = im.size; s = min(w, h)
    return im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))

def rounded(im, radius_frac):
    m = Image.new('L', im.size, 0)
    r = int(im.size[0] * radius_frac)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1), radius=r, fill=255)
    out = im.copy(); out.putalpha(m); return out

def circle(im):
    m = Image.new('L', im.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
    out = im.copy(); out.putalpha(m); return out

def save(im, rel):
    p = os.path.join(RES, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p, optimize=True); print('wrote', rel, im.size)

def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--art', required=True); ap.add_argument('--symbol'); ap.add_argument('--bg')
    a = ap.parse_args()
    art = sq(Image.open(a.art).convert('RGBA'))
    if art.size[0] < 1024: sys.exit('artwork must be >= 1024 px')
    # background colour
    if a.bg: bg = tuple(int(a.bg.lstrip('#')[i:i+2], 16) for i in (0, 2, 4))
    else:
        px = art.convert('RGB'); s = art.size[0]
        cs = [px.getpixel((2, 2)), px.getpixel((s-3, 2)), px.getpixel((2, s-3)), px.getpixel((s-3, s-3))]
        bg = tuple(sum(c[i] for c in cs) // 4 for i in range(3))
    # adaptive layers (108 dp canvas)
    for d, m in DENS.items():
        n = int(108 * m)
        save(art.resize((n, n), Image.LANCZOS), f'mipmap-{d}/ic_launcher_foreground.png')
        save(Image.new('RGBA', (n, n), bg + (255,)), f'mipmap-{d}/ic_launcher_background.png')
    # monochrome layer: white symbol on transparent, symbol occupies the inner ~66 dp
    if a.symbol:
        sym = sq(Image.open(a.symbol).convert('RGBA'))
        alpha = sym.split()[3]
    else:
        g = ImageOps.autocontrast(art.convert('L'))
        alpha = g.point(lambda v: 255 if v > 140 else 0)
    for d, m in DENS.items():
        n = int(108 * m); inner = int(66 * m)
        layer = Image.new('RGBA', (n, n), (0, 0, 0, 0))
        mask = alpha.resize((inner, inner), Image.LANCZOS)
        white = Image.new('RGBA', (inner, inner), (255, 255, 255, 255)); white.putalpha(mask)
        layer.paste(white, ((n - inner) // 2, (n - inner) // 2), white)
        save(layer, f'mipmap-{d}/ic_launcher_monochrome.png')
    # legacy icons (48 dp canvas)
    for d, m in DENS.items():
        n = int(48 * m)
        base = art.resize((n, n), Image.LANCZOS)
        save(rounded(base, 0.18), f'mipmap-{d}/ic_launcher.png')
        save(circle(base), f'mipmap-{d}/ic_launcher_round.png')
    # adaptive XML pointing at the bitmap layers
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
           '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
           '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n</adaptive-icon>\n')
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        p = os.path.join(RES, 'mipmap-anydpi-v26', name); open(p, 'w').write(xml); print('wrote', 'mipmap-anydpi-v26/' + name)
    # retire the old vector layers so nothing references two sets
    for old in ('ic_launcher_background.xml', 'ic_launcher_foreground.xml', 'ic_launcher_monochrome.xml'):
        p = os.path.join(RES, 'drawable', old)
        if os.path.exists(p): os.remove(p); print('removed drawable/' + old)
    # Play store icon
    out = os.path.join(os.path.dirname(__file__), '..', 'build', 'nobonk-play-icon-512.png'); os.makedirs(os.path.dirname(out), exist_ok=True)
    art.convert('RGBA').resize((512, 512), Image.LANCZOS).save(out, optimize=True); print('wrote', os.path.relpath(out))

if __name__ == '__main__': main()
