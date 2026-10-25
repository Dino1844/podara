#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""Cuts the bundled Noto Sans SC subsets from the full OTFs (DESIGN-CRAFT.md
B1).

Full regeneration, from nothing to the three shipped files:

    pip install fonttools                     # 4.54.1 generated the shipped files
    mkdir -p build/font-src tools/font-subset/sources
    curl -L -o build/font-src/NotoSansCJKsc-Regular.otf \
      https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf
    curl -L -o build/font-src/NotoSansCJKsc-Medium.otf \
      https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Medium.otf
    curl -L -o build/font-src/NotoSansCJKsc-Bold.otf \
      https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Bold.otf
    # (~16 MB each, OTTO magic; -L is required, GitHub raw redirects)
    curl -L -o desktop/src/desktopMain/resources/fonts/OFL.txt \
      https://github.com/notofonts/noto-cjk/raw/main/Sans/LICENSE
    python tools/font-subset/build_charset.py   # rebuild charset.txt (see its docstring)
    python tools/font-subset/subset.py

Outputs — all three weights share the one charset in charset.txt:

    desktop/src/desktopMain/resources/fonts/noto-sc-400.otf   (from -Regular)
    desktop/src/desktopMain/resources/fonts/noto-sc-500.otf   (from -Medium)
    desktop/src/desktopMain/resources/fonts/noto-sc-700.otf   (from -Bold)

Budget: <= 4 MB per file (B1). The script prints each output's size and exits
non-zero past the budget — it never trims the character set to fit.

All three weights MUST be cut from the same charset: if 500 has a glyph 400
lacks, the weight axis lies about which characters are safe to render.
"""

import sys
from pathlib import Path

from fontTools import subset

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CHARSET = HERE / "charset.txt"
SRC_DIR = REPO / "build" / "font-src"
OUT_DIR = REPO / "desktop" / "src" / "desktopMain" / "resources" / "fonts"

BUDGET_BYTES = 4 * 1024 * 1024

WEIGHTS = [
    (400, "NotoSansCJKsc-Regular.otf", "noto-sc-400.otf"),
    (500, "NotoSansCJKsc-Medium.otf", "noto-sc-500.otf"),
    (700, "NotoSansCJKsc-Bold.otf", "noto-sc-700.otf"),
]


def load_charset() -> list[int]:
    codepoints: list[int] = []
    for line in CHARSET.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("U+"):
            codepoints.append(int(line[2:], 16))
    if not codepoints:
        sys.exit(f"{CHARSET} parsed to zero codepoints — rerun build_charset.py")
    return codepoints


def cut(weight: int, src_name: str, out_name: str, codepoints: list[int]) -> None:
    src = SRC_DIR / src_name
    dst = OUT_DIR / out_name
    if not src.exists():
        sys.exit(f"missing source font {src} — see the download commands in the docstring")

    options = subset.Options()
    # Keep every layout feature (kern, vert, vkrn, ...): dropping them to save
    # a few KB is how "the face changed" quietly becomes "the shaping broke".
    options.layout_features = "*"
    options.name_IDs = "*"
    options.name_languages = "*"
    options.name_legacy = True
    # Keep the .notdef outline: a codepoint that slips past the charset must
    # render as tofu (visible, diagnosable) — not as a blank.
    options.notdef_outline = True
    options.hinting = True
    # Codepoints the source font itself lacks (e.g. a rare symbol in the floor
    # set) are reported below rather than failing the cut.
    options.ignore_missing_unicodes = True

    font = subset.load_font(str(src), options)
    cmap = font.getBestCmap()
    missing = [cp for cp in codepoints if cp not in cmap]

    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=codepoints)
    subsetter.subset(font)
    dst.parent.mkdir(parents=True, exist_ok=True)
    subset.save_font(font, str(dst), options)

    size = dst.stat().st_size
    glyphs = font["maxp"].numGlyphs
    print(
        f"{out_name}: {size:,} bytes ({size / 1024 / 1024:.2f} MB), "
        f"{glyphs} glyphs, {len(missing)} requested codepoints absent from the source font"
    )
    if missing:
        print(f"  absent: {''.join(chr(c) for c in missing)}")
    if size > BUDGET_BYTES:
        sys.exit(f"{out_name} is over the 4 MB budget — report it, do not trim the charset")


def main() -> None:
    codepoints = load_charset()
    print(f"charset: {len(codepoints)} codepoints from {CHARSET}")
    for weight, src_name, out_name in WEIGHTS:
        cut(weight, src_name, out_name, codepoints)


if __name__ == "__main__":
    main()
