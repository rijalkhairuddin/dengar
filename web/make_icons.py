"""Draws the Dengar app icons (used by the GitHub Pages build)."""
import sys
from PIL import Image, ImageDraw

def icon(size):
    s = 4  # draw big, then shrink for smooth edges
    W = size * s
    im = Image.new("RGB", (W, W), "#11183a")
    d = ImageDraw.Draw(im)
    r = W * 0.36
    c = W / 2
    d.ellipse([c - r, c - r, c + r, c + r], fill="#ff6b4a")
    bw = W * 0.045
    gap = W * 0.085
    n = 7
    hs = [0.10, 0.18, 0.26, 0.34, 0.26, 0.18, 0.10]
    for i, h in enumerate(hs):
        x = c + (i - (n - 1) / 2) * gap
        hh = W * h
        d.rounded_rectangle([x - bw / 2, c - hh / 2, x + bw / 2, c + hh / 2], radius=bw / 2, fill="white")
    return im.resize((size, size), Image.LANCZOS)

out = sys.argv[1] if len(sys.argv) > 1 else "."
icon(192).save(f"{out}/icon-192.png")
icon(512).save(f"{out}/icon-512.png")
icon(180).save(f"{out}/apple-touch-icon.png")
