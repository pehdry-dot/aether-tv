#!/usr/bin/env python3
"""Builds the bundled static fonts (Regular 400, Medium 500) from Google Fonts variable fonts (SIL OFL 1.1).

Sources: https://github.com/google/fonts/tree/main/ofl/<family>/ (downloaded 2026-11-23):
  opensans/OpenSans[wdth,wght].ttf, atkinsonhyperlegiblenext/AtkinsonHyperlegibleNext[wght].ttf,
  lexend/Lexend[wght].ttf, nunito/Nunito[wght].ttf, sourceserif4/SourceSerif4[opsz,wght].ttf
Each is instanced (wdth 100, opsz 20) and subset to Latin + punctuation. Licences: licenses/*-OFL.txt.
Usage: pip install fonttools; python3 tools/build_fonts.py <src-dir with <family>/font.ttf> app/src/main/res/font
"""
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset
src, out = sys.argv[1], sys.argv[2]
fams = {"opensans": "opensans", "atkinsonhyperlegiblenext": "atkinson", "lexend": "lexend", "nunito": "nunito", "sourceserif4": "sourceserif"}
# Latin, Latin-1, Latin Extended-A, general punctuation, arrows/geometric shapes used in labels, currency, degree.
unicodes = list(range(0x20, 0x7F)) + list(range(0xA0, 0x180)) + list(range(0x2000, 0x2070)) + [0x20AC, 0x2122, 0x2190, 0x2192, 0x25B2, 0x25B6, 0x25BC, 0x25C0, 0x25B8, 0x2713, 0x00B0]
for d, name in fams.items():
    for wname, w in (("regular", 400), ("medium", 500)):
        f = TTFont(os.path.join(src, d, "font.ttf"))
        axes = {a.axisTag: a for a in f["fvar"].axes}
        loc = {"wght": w}
        if "wdth" in axes: loc["wdth"] = 100
        if "opsz" in axes: loc["opsz"] = 20  # text optical size: sturdier serifs at TV distance
        inst = instancer.instantiateVariableFont(f, loc, updateFontNames=False)
        opts = subset.Options(); opts.layout_features = ["kern", "liga", "calt", "ccmp", "locl", "mark", "mkmk", "tnum", "lnum"]; opts.name_IDs = ["*"]; opts.notdef_outline = True; opts.glyph_names = False
        sub = subset.Subsetter(opts); sub.populate(unicodes=unicodes); sub.subset(inst)
        p = os.path.join(out, f"{name}_{wname}.ttf"); inst.save(p); print(p, os.path.getsize(p))
