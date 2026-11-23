#!/usr/bin/env python3
"""Procedural alpine wallpaper generator (original, license-free output).

Renders a 3D ridged-multifractal heightfield with a column ("voxel space")
renderer, deferred shading (low sun, cast shadows, snow by slope/height,
sky ambient, cavity AO), atmospheric perspective, perspective cloud layer
with sun-rim lighting, conifer foreground silhouettes, filmic grade, grain.

Usage:
    python generate_wallpaper.py [preset ...] [--preview] [--out DIR]
Presets: alpine_dusk, blue_hour, night  (default: all three)
Deterministic: every random element is seeded.
Requires: numpy, Pillow, scipy
"""
import argparse
import math
import os
import time

import numpy as np
from PIL import Image
from scipy import ndimage

F32 = np.float32

# ----------------------------------------------------------------------------
# Noise
# ----------------------------------------------------------------------------
_GRAD_RNG = np.random.default_rng(1234)
_ANG = _GRAD_RNG.uniform(0, 2 * np.pi, 256)
GX = np.cos(_ANG).astype(F32)
GY = np.sin(_ANG).astype(F32)


def _hash(ix, iy, seed):
    h = (ix * np.uint32(0x27D4EB2D)) ^ (iy * np.uint32(0x165667B1)) ^ np.uint32(seed * 0x9E3779B1 & 0xFFFFFFFF)
    h ^= h >> np.uint32(15)
    h *= np.uint32(0x85EBCA6B)
    h ^= h >> np.uint32(13)
    h *= np.uint32(0xC2B2AE35)
    h ^= h >> np.uint32(16)
    return h >> np.uint32(24)


def perlin(x, y, seed=0):
    """2D gradient noise, roughly in [-1, 1]. x, y float32 arrays."""
    xf0 = np.floor(x)
    yf0 = np.floor(y)
    fx = (x - xf0).astype(F32)
    fy = (y - yf0).astype(F32)
    ix = xf0.astype(np.int64).astype(np.uint32)
    iy = yf0.astype(np.int64).astype(np.uint32)
    one = np.uint32(1)

    def g(hx, hy, dx, dy):
        h = _hash(hx, hy, seed)
        return GX[h] * dx + GY[h] * dy

    u = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
    v = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
    n00 = g(ix, iy, fx, fy)
    n10 = g(ix + one, iy, fx - 1, fy)
    n01 = g(ix, iy + one, fx, fy - 1)
    n11 = g(ix + one, iy + one, fx - 1, fy - 1)
    a = n00 + u * (n10 - n00)
    b = n01 + u * (n11 - n01)
    return (a + v * (b - a)) * F32(1.42)


def _rot(i):
    a = 0.5 + i * 1.17
    return math.cos(a), math.sin(a)


def fbm(x, y, octaves, seed, lac=2.0, gain=0.5):
    out = np.zeros_like(x, dtype=F32)
    amp, fr = 1.0, 1.0
    norm = 0.0
    for i in range(octaves):
        c, s = _rot(i)
        xr = (x * c - y * s) * fr + 17.3 * i
        yr = (x * s + y * c) * fr - 9.1 * i
        out += F32(amp) * perlin(xr.astype(F32), yr.astype(F32), seed + i * 101)
        norm += amp
        amp *= gain
        fr *= lac
    return out / F32(norm)


def ridged(x, y, octaves, seed, lac=2.05, gain=2.1, offset=1.0, H=0.92):
    """Musgrave ridged multifractal, ~[0, 1]."""
    out = np.zeros_like(x, dtype=F32)
    weight = np.ones_like(x, dtype=F32)
    fr = 1.0
    norm = 0.0
    for i in range(octaves):
        c, s = _rot(i)
        xr = (x * c - y * s) * fr + 31.7 * i
        yr = (x * s + y * c) * fr + 7.9 * i
        n = perlin(xr.astype(F32), yr.astype(F32), seed + i * 37)
        sig = F32(offset) - np.abs(n)
        sig = sig * sig * weight
        weight = np.clip(sig * F32(gain), 0, 1)
        w = fr ** (-H)
        out += sig * F32(w)
        norm += w
        fr *= lac
    return out / F32(norm * 0.55)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


# ----------------------------------------------------------------------------
# Presets
# ----------------------------------------------------------------------------
PRESETS = {
    "alpine_dusk": dict(
        file="aether_alpine_dusk.jpg",
        seed=7,
        sun_elev=11.0, sun_az=-18.0, snow_nz=0.50, sun_left=0.07, hero_y=0.072,
        sun_col=(1.00, 0.43, 0.32), sun_int=4.0,
        sky_amb=(0.24, 0.34, 0.62), amb_int=0.22,
        fog_col_left=(0.060, 0.080, 0.130), fog_col_right=(0.105, 0.105, 0.155),
        fog_len=30000.0, fog_max=0.65, valley_fog=0.35,
        sky_top=(0.013, 0.022, 0.048), sky_mid=(0.030, 0.048, 0.092),
        sky_hor=(0.090, 0.110, 0.170), sky_warm=(0.40, 0.15, 0.07),
        cloud_dark=(0.036, 0.050, 0.080), cloud_lit=(1.00, 0.36, 0.28),
        cloud_cov=0.52, cloud_lit_gain=3.6, cloud_amt=1.0, warm_x=0.72, warm_y=0.13, warm_w=0.40,
        stars=0.0, moon=None,
        exposure=1.05, sat=1.06,
        grade_shadow=(0.90, 1.0, 1.08), grade_high=(1.06, 1.0, 0.95),
        bottom_dark=0.72,
    ),
    "blue_hour": dict(
        file="aether_blue_hour.jpg", grid=4096,
        seed=7, sky_seed=11,
        sun_elev=3.0, sun_az=-18.0, snow_nz=0.48, sun_left=0.35,
        sun_col=(0.50, 0.48, 0.78), sun_int=0.45,
        sky_amb=(0.30, 0.42, 0.72), amb_int=0.40,
        fog_col_left=(0.060, 0.085, 0.150), fog_col_right=(0.090, 0.105, 0.175),
        fog_len=28000.0, fog_max=0.70, valley_fog=0.40,
        sky_top=(0.010, 0.020, 0.055), sky_mid=(0.030, 0.055, 0.120),
        sky_hor=(0.110, 0.140, 0.240), sky_warm=(0.20, 0.13, 0.20), glow_x=0.85,
        cloud_dark=(0.030, 0.045, 0.085), cloud_lit=(0.55, 0.42, 0.62),
        cloud_cov=0.44, cloud_lit_gain=0.7, cloud_amt=0.95, cloud_absorb=0.5,
        stars=0.18, moon=None,
        exposure=1.0, sat=0.92,
        grade_shadow=(0.90, 1.0, 1.10), grade_high=(1.0, 1.0, 1.03),
        bottom_dark=0.72,
    ),
    "night": dict(
        file="aether_night.jpg", grid=4096,
        seed=7, sky_seed=23,
        sun_elev=24.0, sun_az=-40.0, snow_nz=0.50, sun_left=0.45,   # the "sun" is the moon here
        sun_col=(0.55, 0.65, 0.95), sun_int=0.42,
        sky_amb=(0.10, 0.14, 0.28), amb_int=0.13,
        fog_col_left=(0.018, 0.026, 0.050), fog_col_right=(0.024, 0.032, 0.060),
        fog_len=30000.0, fog_max=0.70, valley_fog=0.35,
        sky_top=(0.004, 0.007, 0.018), sky_mid=(0.010, 0.016, 0.036),
        sky_hor=(0.026, 0.036, 0.070), sky_warm=(0.030, 0.040, 0.075), glow_x=1.05,
        cloud_dark=(0.010, 0.014, 0.026), cloud_lit=(0.22, 0.27, 0.40),
        cloud_cov=0.30, cloud_lit_gain=0.6, cloud_amt=0.55, cloud_absorb=0.5, warm_x=0.90,
        stars=1.0, moon=None,
        exposure=1.0, sat=0.80,
        grade_shadow=(0.90, 0.98, 1.15), grade_high=(0.98, 1.0, 1.05),
        bottom_dark=0.75,
    ),
}

# Mountain massifs: (screen_x_frac, depth_m, target_peak_screen_y_frac, radius_m, sharpness)
MASSIFS = [
    (0.685, 14000, 0.105, 4800, 1.0),   # hero peak
    (0.80, 13000, 0.185, 3600, 0.9),
    (0.925, 12000, 0.235, 3600, 0.9),
    (1.03, 11000, 0.25, 3600, 0.9),
    (0.565, 15500, 0.20, 3800, 0.9),
    (0.45, 17500, 0.245, 4200, 0.85),
    (0.33, 15500, 0.255, 4000, 0.85),
    (0.19, 15500, 0.26, 3800, 0.85),
    (0.04, 16500, 0.215, 4600, 0.85),
    (0.62, 24000, 0.20, 6000, 0.8),   # distant backdrop
    (0.36, 25000, 0.24, 6500, 0.8),
    (0.10, 23000, 0.21, 6000, 0.8),
    (0.88, 22000, 0.215, 6000, 0.8),
]


# ----------------------------------------------------------------------------
# Generator
# ----------------------------------------------------------------------------
class Generator:
    def __init__(self, preset, preview=False):
        self.p = PRESETS[preset]
        self.name = preset
        self.preview = preview
        self.OW, self.OH = 1920, 1080
        self.ss = 1 if preview else 2
        self.W, self.H = self.OW * self.ss, self.OH * self.ss
        self.N = 2048 if preview else self.p.get("grid", 5120)
        self.fov = math.radians(52.0)
        self.f = (self.W / 2) / math.tan(self.fov / 2)
        self.cam_h = 1800.0
        self.horizon = 0.40 * self.H
        self.dmin, self.dmax = 1500.0, 32000.0
        self.seed = self.p["seed"]

    # --- heightfield -------------------------------------------------------
    def build_terrain(self):
        p = self.p
        N = self.N
        az = math.radians(p["sun_az"])
        self.sun_dir2 = (math.cos(az), math.sin(az))  # horizontal direction toward sun (world x right, y depth)
        # grid: axis a (columns) along sun dir, axis b (rows) perpendicular
        ext = 40000.0
        self.cell = ext / N
        self.gc = np.array([0.0, 16500.0])  # grid centre in world
        self.ext = ext
        tanh = math.tan(self.fov / 2)
        H = np.empty((N, N), F32)
        FG = np.empty((N, N), F32)
        SXg = np.empty((N, N), F32)
        rows_per = max(1, (1 << 22) // N)
        ca, sa = self.sun_dir2
        t0 = time.time()
        for r0 in range(0, N, rows_per):
            r1 = min(N, r0 + rows_per)
            jj, ii = np.meshgrid(np.arange(N, dtype=F32), np.arange(r0, r1, dtype=F32))
            A = (jj + 0.5) * self.cell - ext / 2
            B = (ii + 0.5) * self.cell - ext / 2
            X = (self.gc[0] + A * ca - B * sa).astype(F32)
            Y = (self.gc[1] + A * sa + B * ca).astype(F32)
            H[r0:r1], FG[r0:r1] = self._height(X, Y, tanh)
            SXg[r0:r1] = X / (np.maximum(Y, 100) * 2 * tanh) + 0.5
        print(f"  terrain {N}x{N} in {time.time()-t0:.1f}s, h range {H.min():.0f}..{H.max():.0f}")
        self.Hgrid = H
        self.FG = FG
        self.SX = SXg

    def _height(self, X, Y, tanh):
        """Large-scale height (m) at world X (lateral), Y (depth)."""
        s = self.seed
        km = F32(1 / 1000.0)
        x = X * km
        y = Y * km
        wx = fbm(x * 0.15, y * 0.15, 4, s + 500)
        wy = fbm(x * 0.15 + 5.2, y * 0.15 + 1.3, 4, s + 600)
        xw = x + wx * 0.9
        yw = y + wy * 0.9
        R = ridged(xw * 0.21, yw * 0.21, 7, s + 1000, lac=2.1, gain=2.0, H=1.05)
        base = 850 + 220 * fbm(x * 0.06, y * 0.06, 3, s + 50)
        # massif envelope (smooth union of peaks + a continuous range behind)
        acc = np.zeros_like(X)
        P = 8.0
        hero_w = np.zeros_like(X)
        for k, (sx, d, yp, rad, sharp) in enumerate(MASSIFS):
            u = (sx - 0.5) * 2 * tanh
            if k == 0:
                yp = self.p.get("hero_y", yp)
            hpk = self.cam_h + (self.horizon - yp * self.H) / self.f * d + d * d / (2 * 6371000.0) * 0.87
            mx, my = u * d, d
            r = np.sqrt((X - mx) ** 2 + ((Y - my) * 1.2) ** 2) / rad
            prof = 0.55 * np.clip(1 - r, 0, None) ** (1.15 * sharp) + 0.45 * np.exp(-2.2 * r * r)
            if k == 0:  # hero: sharper pyramid
                prof = 0.78 * np.clip(1 - r, 0, None) ** 1.05 + 0.22 * np.exp(-2.2 * r * r)
                hero_w = np.clip(1 - r / 0.45, 0, 1) ** 1.5
            e = prof * (hpk - 850) / (0.90 if k == 0 else 0.86)
            acc += (e / 3000.0) ** P
        rng_fill = (800 + 500 * fbm(x * 0.08, y * 0.08, 3, s + 60)) * smoothstep(9000, 13000, Y) / 3000.0
        acc += np.clip(rng_fill, 0, None) ** P
        M = (acc ** (1 / P) * 3000.0).astype(F32)
        Tn = np.clip((R - 0.3) / 1.3, 0, 1.1)
        mt = M / 3000.0
        nm = (0.45 + 0.65 * Tn) * (1 - hero_w) + (0.80 + 0.26 * Tn) * hero_w
        Hm = base + M * nm + np.sqrt(np.clip(mt, 0, None)) * 260 * (Tn - 0.5) * (1 - 0.5 * hero_w)
        # small rolling relief everywhere
        Hm += 90 * fbm(x * 0.9, y * 0.9, 4, s + 70)
        # foreground forested crest (occludes mountain bases)
        sx = X / (np.maximum(Y, 100) * 2 * tanh) + 0.5
        yt = (0.475 + 0.012 * np.sin(sx * 9.0 + 1.0) - 0.015 * np.exp(-((sx - 0.30) / 0.12) ** 2)
              - 0.20 * smoothstep(0.70, 1.08, sx) ** 1.2)
        dcrest = 4200 - 1500 * smoothstep(0.65, 1.05, sx)
        hcrest = self.cam_h - (yt * self.H - self.horizon) / self.f * dcrest
        prof = np.exp(-(((Y - dcrest) / 1300.0) ** 2))
        prof = np.where(Y < dcrest, np.maximum(prof, 0.55 + 0.45 * smoothstep(dcrest - 2500, dcrest, Y)) * smoothstep(800, 2000, Y), prof)
        F = (hcrest + 60 * fbm(x * 1.5, y * 1.5, 5, s + 80)) * prof
        H = np.maximum(Hm, F + base * (1 - prof) * 0)
        self_fg = smoothstep(dcrest + 2500, dcrest + 800, Y)
        return np.maximum(H, base).astype(F32), self_fg.astype(F32)

    def erode(self):
        """Gully / erosion detail aligned with the fall line (after Clay John)."""
        H = self.Hgrid
        N = self.N
        c = self.cell
        t0 = time.time()
        Hs = ndimage.gaussian_filter(H, 120.0 / c)
        gb, ga = np.gradient(Hs, c)
        slope = np.sqrt(ga ** 2 + gb ** 2)
        amp_mask = smoothstep(0.12, 0.7, slope) * smoothstep(1200, 2000, Hs) * (1 - self.FG)
        out = np.zeros_like(H)
        rows_per = max(1, (1 << 21) // N)
        octs = [(1 / 0.42, 1.0), (1 / 0.20, 0.55), (1 / 0.10, 0.30), (1 / 0.05, 0.16)]
        if self.preview:
            octs = octs[:3]
        for r0 in range(0, N, rows_per):
            r1 = min(N, r0 + rows_per)
            jj, ii = np.meshgrid(np.arange(N, dtype=F32), np.arange(r0, r1, dtype=F32))
            A = jj * F32(c / 1000.0)
            B = ii * F32(c / 1000.0)
            dx = ga[r0:r1]
            dy = gb[r0:r1]
            hacc = np.zeros_like(A)
            hdx = np.zeros_like(A)
            hdy = np.zeros_like(A)
            for k, (fr, amp) in enumerate(octs):
                # direction perpendicular to (gradient + branch from previous octaves)
                gx = dx + hdx * 0.0012 * 0
                gy = dy
                gx = dx * 1.0 + hdx * 0.35
                gy = dy * 1.0 + hdy * 0.35
                ln = np.sqrt(gx * gx + gy * gy) + 1e-6
                px, py = A * fr, B * fr
                ip_x, ip_y = np.floor(px), np.floor(py)
                fx, fy = px - ip_x, py - ip_y
                ix = ip_x.astype(np.int64).astype(np.uint32)
                iy = ip_y.astype(np.int64).astype(np.uint32)
                va = np.zeros_like(A)
                vdx = np.zeros_like(A)
                vdy = np.zeros_like(A)
                wt = np.zeros_like(A)
                dirx, diry = gy / ln, -gx / ln
                for oi in (-1, 0, 1, 2):
                    for oj in (-1, 0, 1, 2):
                        hx = _hash(ix + np.uint32(oi & 0xFFFFFFFF), iy + np.uint32(oj & 0xFFFFFFFF), self.seed + 300 + k).astype(F32) / 255.0
                        hy = _hash(iy + np.uint32(oj & 0xFFFFFFFF), ix + np.uint32(oi & 0xFFFFFFFF), self.seed + 400 + k).astype(F32) / 255.0
                        ppx = fx - oi - hx
                        ppy = fy - oj - hy
                        d2 = ppx * ppx + ppy * ppy
                        w = np.exp(-d2 * 2.0)
                        mag = (ppx * dirx + ppy * diry) * F32(2 * np.pi)
                        va += np.cos(mag) * w
                        sn = -np.sin(mag) * w
                        vdx += sn * dirx
                        vdy += sn * diry
                        wt += w
                va /= wt
                vdx /= wt
                vdy /= wt
                hacc += va * amp
                hdx += vdx * amp * fr * 0.03
                hdy += vdy * amp * fr * 0.03
            out[r0:r1] = hacc
        self.erosion = out
        self.Hgrid = (H + out * 55.0 * amp_mask - 30.0 * amp_mask).astype(F32)
        print(f"  erosion in {time.time()-t0:.1f}s")

    # --- shading in grid space -------------------------------------------
    def shade_terrain(self):
        p = self.p
        H = self.Hgrid
        c = self.cell
        t0 = time.time()
        # gradient in grid coords (a along columns, b along rows)
        dha = np.gradient(H, axis=1) / c
        dhb = np.gradient(H, axis=0) / c
        nlen = np.sqrt(dha ** 2 + dhb ** 2 + 1)
        nx, ny, nz = -dha / nlen, -dhb / nlen, 1 / nlen
        el = math.radians(p["sun_elev"])
        L = np.array([math.cos(el), 0.0, math.sin(el)])  # sun along +a in grid space
        ndl = np.clip(nx * L[0] + nz * L[2], 0, 1)
        # large-scale form lighting so big faces read clearly through gully detail
        Hs = ndimage.gaussian_filter(H, 220.0 / c)
        sa_ = np.gradient(Hs, axis=1) / c
        sb_ = np.gradient(Hs, axis=0) / c
        sl = np.sqrt(sa_ ** 2 + sb_ ** 2 + 1)
        ndl_s = np.clip((-sa_ / sl) * L[0] + (1 / sl) * L[2], 0, 1)
        ndl = ndl * 0.55 + ndl_s * 0.45 * smoothstep(0.0, 0.15, ndl + 0.1)
        # cast shadows: sweep from sun side (+a) toward -a
        shadow = np.ones_like(H)
        if el > -0.01:
            tl = math.tan(max(el, 0.004)) * c
            hor = H[:, -1].copy()
            for j in range(H.shape[1] - 2, -1, -1):
                hor = np.maximum(hor - tl, H[:, j + 1])
                diff = H[:, j] - (hor - tl)
                shadow[:, j] = np.clip(diff / 25.0 + 0.5, 0, 1)
            shadow = ndimage.gaussian_filter(shadow, 1.0)
        else:
            shadow[:] = 0.0
        # cavity / AO
        blur = ndimage.gaussian_filter(H, 60.0 / c)
        cav = np.clip(0.5 + (H - blur) / 40.0, 0, 1)
        blur2 = ndimage.gaussian_filter(H, 400.0 / c)
        ao = np.clip(0.75 + (H - blur2) / 500.0, 0.35, 1.0)
        # snow: flat-ish & above snowline, noisy threshold
        N = self.N
        jj, ii = np.meshgrid(np.arange(N, dtype=F32), np.arange(N, dtype=F32))
        sn = fbm(jj * F32(c / 140.0), ii * F32(c / 140.0), 5, self.seed + 77)
        sn2 = fbm(jj * F32(c / 35.0), ii * F32(c / 35.0), 3, self.seed + 78)
        ero = self.erosion
        thresh = p.get("snow_nz", 0.60) + 0.02 * sn + 0.015 * sn2 + 0.13 * ero - 0.10 * (cav - 0.5)
        # snow decided on a slightly smoothed normal (resolution independent)
        Hn = ndimage.gaussian_filter(H, max(0.7, 22.0 / c))
        gna = np.gradient(Hn, axis=1) / c
        gnb = np.gradient(Hn, axis=0) / c
        nzs = 1 / np.sqrt(gna ** 2 + gnb ** 2 + 1)
        del Hn, gna, gnb
        snow = smoothstep(thresh - 0.06, thresh + 0.04, nzs)
        snow *= smoothstep(1700, 2200, H + 250 * sn)
        snow = np.clip(snow + 0.20 * smoothstep(2600, 3800, H) * smoothstep(0.30, 0.50, nzs), 0, 1)
        snow *= 0.85 + 0.15 * cav
        # forest below treeline
        forest = smoothstep(1800, 1450, H + 200 * sn) * smoothstep(0.55, 0.75, nz)
        forest = np.maximum(forest, self.FG)
        snow *= (1 - self.FG)
        rock = np.array([0.055, 0.053, 0.056], F32) * (0.6 + 0.8 * cav[..., None])
        snowc = np.array([0.80, 0.82, 0.86], F32)
        forc = np.array([0.025, 0.035, 0.035], F32)
        alb = rock * (1 - snow[..., None]) + snowc * snow[..., None]
        alb = alb * (1 - forest[..., None]) + forc * forest[..., None]
        sunc = np.array(p["sun_col"], F32) * p["sun_int"]
        ambc = np.array(p["sky_amb"], F32) * p["amb_int"]
        # cloud-shadow mask: keep the left third of the frame calmer
        sx = self.SX
        jj2, ii2 = np.meshgrid(np.arange(N, dtype=F32), np.arange(N, dtype=F32))
        cs = fbm(jj2 * F32(c / 2500.0), ii2 * F32(c / 2500.0), 3, self.seed + 91)
        lm = p.get("sun_left", 1.0)
        lmask = np.clip(lm + (1 - lm) * smoothstep(0.40, 0.66, sx + 0.22 * cs), 0, 1)
        del self.SX
        lmask = lmask * (1 - 0.85 * self.FG)
        direct = (ndl * shadow * lmask)[..., None] * sunc
        sky = ((0.40 + 0.60 * nz) * ao * (0.55 + 0.45 * cav))[..., None] * ambc
        # warm bounce from sunlit sky on sun-facing side
        bounce = (np.clip(nx, 0, 1) * 0.12 * ao * lmask)[..., None] * sunc * 0.25
        col = alb * (direct + sky + bounce)
        self.Cgrid = col.astype(F32)
        del self.erosion
        print(f"  shading in {time.time()-t0:.1f}s")

    # --- render ------------------------------------------------------------
    def world_to_grid(self, X, Y):
        ca, sa = self.sun_dir2
        dx = X - self.gc[0]
        dy = Y - self.gc[1]
        A = dx * ca + dy * sa
        B = -dx * sa + dy * ca
        j = (A + self.ext / 2) / self.cell - 0.5
        i = (B + self.ext / 2) / self.cell - 0.5
        return i, j

    def sample(self, grid, i, j, order=1):
        return ndimage.map_coordinates(grid, [i, j], order=order, mode="nearest")

    def render_terrain(self):
        W, Hh = self.W, self.H
        nsteps = 2500 if self.preview else 7000
        d = (self.dmin * (self.dmax / self.dmin) ** (np.arange(nsteps) / (nsteps - 1))).astype(F32)
        u = ((np.arange(W) + 0.5 - W / 2) / self.f).astype(F32)
        ys = np.empty((nsteps, W), F32)
        t0 = time.time()
        chunk = max(1, (1 << 23) // W)
        for s0 in range(0, nsteps, chunk):
            s1 = min(nsteps, s0 + chunk)
            D = d[s0:s1, None]
            X = (u[None, :] * D).astype(F32)
            Y = np.broadcast_to(D, X.shape).astype(F32)
            i, j = self.world_to_grid(X, Y)
            h = self.sample(self.Hgrid, i.ravel(), j.ravel()).reshape(X.shape)
            h = h - D * D / (2 * 6371000.0) * 0.87
            ys[s0:s1] = self.horizon - (h - self.cam_h) / D * self.f
        cm = np.minimum.accumulate(ys, axis=0)
        rows = np.arange(Hh, dtype=F32) + 0.5
        idx = np.empty((Hh, W), np.int32)
        for c in range(W):
            idx[:, c] = np.searchsorted(-cm[:, c], -rows, side="left")
        sky = idx >= nsteps
        idc = np.clip(idx, 1, nsteps - 1)
        cols = np.broadcast_to(np.arange(W), idc.shape)
        prev = cm[idc - 1, cols]
        cur = ys[idc, cols]
        t = np.clip((prev - rows[:, None]) / np.maximum(prev - cur, 1e-4), 0, 1)
        dd = d[idc - 1] + t * (d[idc] - d[idc - 1])
        dd = np.where(idx == 0, d[0], dd)
        self.depth = np.where(sky, np.inf, dd).astype(F32)
        self.skymask = sky
        X = u[None, :] * dd
        Y = dd
        i, j = self.world_to_grid(X, Y)
        self.hit_h = self.sample(self.Hgrid, i.ravel(), j.ravel()).reshape(Hh, W)
        col = np.empty((Hh, W, 3), F32)
        for k in range(3):
            col[..., k] = self.sample(self.Cgrid[..., k], i.ravel(), j.ravel()).reshape(Hh, W)
        self.tcol = col
        print(f"  terrain render in {time.time()-t0:.1f}s")

    # --- sky ---------------------------------------------------------------
    def render_sky(self):
        p = self.p
        W, Hh = self.W, self.H
        yy, xx = np.mgrid[0:Hh, 0:W].astype(F32)
        xn = xx / W
        yn = yy / Hh
        hz = self.horizon / Hh
        v = (self.horizon - yy) / self.f   # elevation slope
        t = np.clip(yn / hz, 0, 1.3)
        top = np.array(p["sky_top"], F32)
        mid = np.array(p["sky_mid"], F32)
        hor = np.array(p["sky_hor"], F32)
        warm = np.array(p["sky_warm"], F32)
        a = smoothstep(0.0, 0.8, t)[..., None]
        b = smoothstep(0.5, 1.05, t)[..., None]
        sky = top * (1 - a) + mid * a
        sky = sky * (1 - b) + hor * b
        sx = p.get("glow_x", 0.80)
        glow = np.exp(-(((xn - sx) / 0.45) ** 2) - (((yn - hz) / 0.28) ** 2))
        sky = sky + warm[None, None, :] * (glow * 0.6)[..., None]
        tl = np.exp(-((xn / 0.55) ** 2) - ((yn / 0.45) ** 2))
        sky *= (1 - 0.30 * tl)[..., None]
        # --- clouds on a perspective plane, lit from the low sun (lower right)
        tt = 1.0 / (np.maximum(v, -0.02) + 0.18)
        cx = ((xx - W / 2) / self.f) * tt
        cy = tt
        s = p.get("sky_seed", self.seed) + 9000
        sc = p.get("cloud_scale", 0.85)
        wx = fbm(cx * 0.30, cy * 0.30, 3, s + 1)
        wy = fbm(cx * 0.30 + 3.1, cy * 0.30 + 7.7, 3, s + 2)
        qx = (cx * 0.9 + 0.45 * wx) * sc
        qy = (cy * 2.0 + 0.45 * wy) * sc
        cov = p["cloud_cov"]
        band = np.exp(-(((xn - p.get("cloud_cx", 0.62)) / 0.45) ** 2) - (((yn - 0.15) / 0.17) ** 2))
        cmod = 0.22 * band - 0.20 * tl - 0.12 * smoothstep(0.8, 1.0, t) - 0.06
        zc = (0.5 - cov) * 2.5

        def density(ax, ay, raw=False):
            shape = fbm(ax, ay, 4, s + 3, gain=0.5) / 0.175
            det = 0.5 - np.abs(fbm(ax * 3.2, ay * 3.2, 5, s + 13, gain=0.55))
            z = shape + cmod * 6 + 1.8 * (det - 0.25)
            d_ = smoothstep(zc - 0.25, zc + 0.75, z)
            return (d_, z) if raw else d_

        D, Z = density(qx, qy, raw=True)
        # relief shading of the cloud "surface" (lumpy texture), light from lower right
        Zs = ndimage.gaussian_filter(Z, 1.8 * self.ss)
        gy_, gx_ = np.gradient(Zs)
        rel = np.clip(0.40 + (gx_ * 0.8 + gy_ * 0.6) * (-10.0 * self.ss), 0, 1)
        # optical depth toward the sun (sun sits low on the right, beyond the horizon)
        odep = np.zeros_like(D)
        for k, st in enumerate((0.05, 0.11, 0.20, 0.34)):
            odep += density(qx + st * 0.55 * sc, qy + st * 1.0 * sc) * (1.0 if k < 2 else 0.8)
        lit = np.exp(-p.get("cloud_absorb", 0.40) * odep) * (0.25 + 0.75 * rel)
        warmmask = np.clip(np.exp(-(((xn - p.get("warm_x", 0.62)) / p.get("warm_w", 0.34)) ** 2) - (((yn - p.get("warm_y", 0.17)) / 0.17) ** 2)), 0, 1)
        dark = np.array(p["cloud_dark"], F32)
        litc = np.array(p["cloud_lit"], F32)
        lowboost = 0.35 + 0.65 * smoothstep(0.0, 0.85, t)
        amb = dark[None, None, :] * (1.15 - 0.45 * D * (1 - lit))[..., None]
        ccol = amb + litc[None, None, :] * (p["cloud_lit_gain"] * lit * (0.10 + 0.90 * warmmask) * lowboost)[..., None]
        hf = smoothstep(-0.01, 0.03, v)
        alpha = np.clip(D * p["cloud_amt"], 0, 0.97) * hf
        sky = sky * (1 - alpha[..., None]) + ccol * alpha[..., None]
        # stars
        if p["stars"] > 0:
            rng = np.random.default_rng(self.seed + 4242)
            n = int(3000 * (W * Hh) / (1920 * 1080) / self.ss ** 2)
            sxp = rng.uniform(0, W, n)
            syp = rng.uniform(0, self.horizon, n)
            mag = np.minimum(rng.pareto(1.8, n) * 0.05, 3.0)
            star = np.zeros((Hh, W), F32)
            np.add.at(star, (syp.astype(int), sxp.astype(int)), mag)
            star = ndimage.gaussian_filter(star, 0.55 * self.ss) * (3.0 * self.ss ** 2)
            star *= (1 - alpha) * smoothstep(0.0, 0.12, v) * p["stars"]
            tint = np.array([0.9, 0.95, 1.05], F32)
            sky += star[..., None] * tint
        if p["moon"] is not None:
            mx, my, mr = p["moon"]
            r = np.sqrt(((xn - mx) * W / Hh) ** 2 + (yn - my) ** 2)
            disk = smoothstep(mr, mr - 0.0015, r)
            # subtle maria
            mar = 0.85 + 0.15 * fbm(xn * 300, yn * 300, 3, 5)
            halo = np.exp(-(r / (mr * 3)) ** 2) * 0.12 + np.exp(-(r / (mr * 14)) ** 2) * 0.06
            mcol = np.array([0.95, 0.97, 1.0], F32) * 1.4
            sky = sky * (1 - disk[..., None]) + (mcol * mar[..., None]) * disk[..., None] * (1 - 0.7 * alpha[..., None])
            sky += np.array([0.30, 0.38, 0.55], F32) * (halo * (1 - 0.4 * alpha))[..., None]
        self.sky = sky.astype(F32)

    # --- composite -------------------------------------------------------------
    def composite(self):
        p = self.p
        W, Hh = self.W, self.H
        xn = (np.arange(W, dtype=F32) / W)[None, :]
        d = np.where(self.skymask, self.dmax, self.depth)
        fog = (1 - np.exp(-d / p["fog_len"])) * p["fog_max"]
        # valley haze: denser at low altitude
        hfog = np.exp(-np.clip(self.hit_h - 900, 0, None) / 700.0) * (1 - np.exp(-d / 9000.0)) * p.get("valley_fog", 0.45)
        fog = np.clip(fog + hfog * (1 - fog), 0, 0.95)
        fl = np.array(p["fog_col_left"], F32)
        fr = np.array(p["fog_col_right"], F32)
        wm = smoothstep(0.25, 1.0, xn)[..., None]
        fogc = fl * (1 - wm) + fr * wm
        tcol = self.tcol
        fgpx = (~self.skymask) & (self.depth < 7500)
        if fgpx.any():
            rr, cc = np.nonzero(fgpx)
            dd = self.depth[rr, cc]
            ux = (cc + 0.5 - W / 2) / self.f
            Xw = (ux * dd).astype(F32)
            Yw = dd.astype(F32)
            ntex = fbm(Xw / F32(9.0), Yw / F32(9.0), 3, self.seed + 55)
            nbig = fbm(Xw / F32(160.0), Yw / F32(160.0), 3, self.seed + 56)
            tex = np.clip(0.80 + 0.9 * ntex + 0.6 * nbig, 0.35, 1.6)
            tcol = tcol.copy()
            tcol[rr, cc] *= tex[:, None]
        terr = tcol * (1 - fog[..., None]) + fogc * fog[..., None]
        img = np.where(self.skymask[..., None], self.sky, terr)
        img = self.trees(img)
        return img

    def trees(self, img):
        """Conifer silhouettes along the foreground crest."""
        W, Hh = self.W, self.H
        fgm = (~self.skymask) & (self.depth < 7500)
        has = fgm.any(axis=0)
        ytop = np.where(has, fgm.argmax(axis=0), Hh)
        rng = np.random.default_rng(self.seed + 31)
        out = img.copy()
        cover = np.zeros((Hh, W), F32)
        colimg = np.zeros((Hh, W, 3), F32)
        n = int(W * 0.9)
        for _ in range(n):
            x0 = rng.uniform(0, W)
            xi = int(x0)
            if ytop[xi] >= Hh - 2:
                continue
            yb = ytop[xi] + rng.uniform(0.5, 4.0) * self.ss
            dd = float(self.depth[min(Hh - 1, int(yb) + 2 * self.ss), xi])
            if not np.isfinite(dd):
                continue
            th = rng.uniform(16, 30) / dd * self.f
            if th < 1.5 * self.ss:
                continue
            tw = th * rng.uniform(0.17, 0.24)
            y_top = yb - th
            ya, yb2 = int(max(0, y_top - 1)), int(min(Hh, yb + 1))
            xa, xb = int(max(0, x0 - tw - 2)), int(min(W, x0 + tw + 2))
            if ya >= yb2 or xa >= xb:
                continue
            ty, tx = np.mgrid[ya:yb2, xa:xb].astype(F32)
            rel = np.clip((ty - y_top) / th, 0, 1)
            tiers = 6 + rng.integers(0, 4)
            saw = (rel * tiers + rng.random()) % 1.0
            half = tw * rel ** 0.85 * (0.55 + 0.45 * saw) + 0.35 * self.ss
            m = np.clip(half - np.abs(tx - x0) + 0.5, 0, 1) * (ty >= y_top - 0.5)
            c = img[min(Hh - 1, int(yb) + 3 * self.ss), xi]
            sub = cover[ya:yb2, xa:xb]
            upd = m > sub
            sub[upd] = m[upd]
            colimg[ya:yb2, xa:xb][upd] = c
        out = img * (1 - cover[..., None]) + colimg * cover[..., None]
        return out

    def post(self, img):
        p = self.p
        W, Hh = self.W, self.H
        img = img * p["exposure"]
        # soft bloom around bright highlights (lit snow, cloud rims)
        br = np.clip(img - 0.6, 0, None)
        bl = np.stack([ndimage.gaussian_filter(br[..., k], 14 * self.ss) for k in range(3)], -1)
        img = img + bl * p.get("bloom", 0.35)
        # filmic tonemap (ACES fit)
        a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
        x = img
        img = np.clip((x * (a * x + b)) / (x * (c * x + d) + e), 0, 1)
        # desaturate
        lum = (img * np.array([0.2126, 0.7152, 0.0722], F32)).sum(-1, keepdims=True)
        img = lum + (img - lum) * p["sat"]
        # split tone
        gs = np.array(p["grade_shadow"], F32)
        gh = np.array(p["grade_high"], F32)
        w = smoothstep(0.0, 0.6, lum)
        img = img * (gs * (1 - w) + gh * w)
        # bloom-ish glow on bright parts (cheap)
        yy = (np.arange(Hh, dtype=F32) / Hh)[:, None]
        xx = (np.arange(W, dtype=F32) / W)[None, :]
        # darken bottom ~45% for UI legibility
        bot = smoothstep(0.42, 1.0, yy) * p["bottom_dark"]
        img = img * (1 - bot[..., None] * 0.85)
        # left third calmer
        left = np.exp(-((xx / 0.45) ** 2)) * smoothstep(0.0, 0.5, yy + 0.3)
        img = img * (1 - 0.30 * left[..., None])
        # vignette
        r = np.sqrt(((xx - 0.55) / 0.85) ** 2 + ((yy - 0.42) / 0.75) ** 2)
        img = img * (1 - 0.45 * smoothstep(0.55, 1.25, r))[..., None]
        img = np.clip(img, 0, 1)
        # downsample
        if self.ss > 1:
            img = img.reshape(self.OH, self.ss, self.OW, self.ss, 3).mean(axis=(1, 3))
        # gamma
        img = np.clip(img, 0, 1) ** (1 / 2.2)
        # film grain + dither
        rng = np.random.default_rng(self.seed + 99)
        g = rng.normal(0, 1, (self.OH, self.OW)).astype(F32)
        g = ndimage.gaussian_filter(g, 0.6) * 1.6
        lumg = img.mean(-1, keepdims=True)
        amp = 0.010 + 0.012 * (1 - lumg)
        img = img + g[..., None] * amp
        img = img + rng.uniform(-0.5, 0.5, img.shape).astype(F32) / 255.0
        return np.clip(img * 255 + 0.5, 0, 255).astype(np.uint8)

    def run(self, out_dir):
        t0 = time.time()
        print(f"[{self.name}] preview={self.preview}")
        self.build_terrain()
        self.erode()
        self.shade_terrain()
        self.render_terrain()
        self.render_sky()
        img = self.composite()
        out = self.post(img)
        fn = self.p["file"]
        if self.preview:
            fn = fn.replace(".jpg", "_preview.jpg")
        path = os.path.join(out_dir, fn)
        Image.fromarray(out).save(path, quality=88, optimize=True, progressive=True, subsampling="4:2:0")
        print(f"  wrote {path} ({os.path.getsize(path)/1024:.0f} KB) total {time.time()-t0:.1f}s")
        return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("presets", nargs="*", default=list(PRESETS))
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--grid", type=int, default=0, help="override heightfield resolution")
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    a = ap.parse_args()
    for pr in a.presets:
        G = Generator(pr, a.preview)
        if a.grid:
            G.N = a.grid
        G.run(a.out)


if __name__ == "__main__":
    main()
