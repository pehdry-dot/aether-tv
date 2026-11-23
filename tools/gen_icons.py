#!/usr/bin/env python3
"""Regenerates core/designsystem/AetherIcons.kt from Lucide SVGs (ISC license).

Usage: python3 tools/gen_icons.py <dir-with-lucide-svgs>
Download SVGs from https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/<name>.svg
"""
import os, re, sys, xml.etree.ElementTree as ET

NS = '{http://www.w3.org/2000/svg}'
RENAME = {'trash': 'Trash', 'circle-question-mark': 'Help', 'a-large-small': 'TextSize', 'code-xml': 'Code',
          'square-play': 'Media', 'layout-grid': 'AppsGrid', 'gamepad-2': 'Games', 'house': 'Home',
          'gamepad': 'Controller', 'radio-tower': 'LiveTv', 'ethernet-port': 'Ethernet'}

def f(v): return '%g' % float(v)

def to_d(e):
    t, a = e.tag.replace(NS, ''), e.attrib
    if t == 'path': return a['d']
    if t == 'circle':
        cx, cy, r = float(a['cx']), float(a['cy']), float(a['r'])
        return f"M{f(cx-r)} {f(cy)}a{f(r)} {f(r)} 0 1 0 {f(2*r)} 0a{f(r)} {f(r)} 0 1 0 {f(-2*r)} 0"
    if t == 'ellipse':
        cx, cy, rx, ry = (float(a[k]) for k in ('cx', 'cy', 'rx', 'ry'))
        return f"M{f(cx-rx)} {f(cy)}a{f(rx)} {f(ry)} 0 1 0 {f(2*rx)} 0a{f(rx)} {f(ry)} 0 1 0 {f(-2*rx)} 0"
    if t == 'line': return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if t in ('polyline', 'polygon'):
        p = re.split(r'[\s,]+', a['points'].strip())
        return 'M' + 'L'.join(p[i] + ' ' + p[i+1] for i in range(0, len(p), 2)) + ('Z' if t == 'polygon' else '')
    if t == 'rect':
        x, y, w, h = (float(a.get(k, 0)) for k in ('x', 'y', 'width', 'height'))
        rx = float(a.get('rx', a.get('ry', 0))); ry = float(a.get('ry', rx))
        if rx == 0: return f"M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}Z"
        return (f"M{f(x+rx)} {f(y)}h{f(w-2*rx)}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(ry)}v{f(h-2*ry)}"
                f"a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(ry)}h{f(-(w-2*rx))}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(-ry)}"
                f"v{f(-(h-2*ry))}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(-ry)}Z")
    raise ValueError(t)

def main(src):
    lines = []
    for fn in sorted(os.listdir(src)):
        if not fn.endswith('.svg'): continue
        n = fn[:-4]
        root = ET.parse(os.path.join(src, fn)).getroot()
        ds = [to_d(e) for e in root.iter() if e.tag.replace(NS, '') in ('path', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'rect')]
        name = RENAME.get(n, ''.join(p.capitalize() for p in n.split('-')))
        lines.append(f'    val {name}: ImageVector by lazy {{ lucide("{name}", ' + ', '.join(f'"{d}"' for d in ds) + ') }')
    print('\n'.join(lines))

if __name__ == '__main__':
    main(sys.argv[1])
