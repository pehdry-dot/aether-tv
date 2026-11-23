#!/usr/bin/env python3
"""Turns a product photo on a white background into a transparent PNG for Aether device cards.

The background is flood-filled from the borders, so white parts inside the product are kept. The grey floor shadow
becomes a soft, semi-transparent black shadow instead of a grey shape.

Usage: python3 tools/cutout_product_photo.py photo.jpg app/src/main/res/drawable-nodpi/device_xbox_series_x.png
"""
import sys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

def cutout(src, dst, max_h=420):
    im = Image.open(src).convert('RGB')
    a = np.asarray(im).astype(np.float32)
    lum = a.mean(-1)
    chroma = a.max(-1) - a.min(-1)
    # Background candidates: bright, unsaturated pixels connected to the image border.
    bgish = (lum > 92) & (chroma < 28)
    lab, _ = ndimage.label(bgish)
    border = np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))
    bg = np.isin(lab, border[border > 0])
    alpha = np.ones_like(lum)
    # Pure background -> transparent; shadow (darker grey, still background-connected) -> soft translucent black.
    shadow_strength = np.clip((245 - lum) / 150, 0, 1) * 0.75
    alpha = np.where(bg, shadow_strength, 1.0)
    rgb = np.where(bg[..., None], 0, a)
    alpha = ndimage.gaussian_filter(alpha, 0.6)
    out = Image.fromarray(np.dstack([rgb, alpha * 255]).astype(np.uint8), 'RGBA')
    box = Image.fromarray((alpha * 255).astype(np.uint8)).point(lambda v: 255 if v > 12 else 0).getbbox()
    out = out.crop(box)
    if out.height > max_h:
        out = out.resize((round(out.width * max_h / out.height), max_h), Image.LANCZOS)
    out.save(dst, optimize=True)
    print(dst, out.size)

if __name__ == '__main__':
    cutout(sys.argv[1], sys.argv[2])
