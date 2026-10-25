#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""Builds charset.txt — the single character set that subset.py cuts all three
Noto Sans SC weights from (DESIGN-CRAFT.md B1).

Character sources, in order:

1. The 3500 common-character tables under sources/ (copies of two public
   files that both claim to be the 1988 《现代汉语常用字表》; the copies
   disagree by a few hundred entries, so the union is taken — a missing glyph
   is the only real risk B1 names, and a few hundred extra chars stay far
   under the 4 MB/weight budget):
       sources/common-3500-a.txt  from
         https://raw.githubusercontent.com/kaienfr/Font/master/learnfiles/常用汉字库 3500.txt
       sources/common-3500-b.txt  from
         https://raw.githubusercontent.com/elephantnose/characters/master/3500常用汉字.txt
2. Every string literal inside util/Strings.kt's `en` and `zh` mapOf blocks,
   decoded through Kotlin's escape rules ($ \ " \n \t \uXXXX ...). Keys and
   values both count: keys are ASCII, so this costs nothing and stays honest
   to "全部字符串字面量".
3. A fallback floor: ASCII printable (U+0020-U+007E), common CJK punctuation,
   the fullwidth forms block (U+FF01-U+FF5E) and a curated symbol set — so
   brand-new UI copy stays renderable even before this pipeline is rerun.

Output: charset.txt, one `U+XXXX` per line, `#` comments. Whitespace-bearing
characters are unambiguous in codepoint notation, which a bare-char file is
not (a line holding only a space is invisible).

Rerun:  python tools/font-subset/build_charset.py
Then:   python tools/font-subset/subset.py
"""

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
STRINGS_KT = REPO / "desktop" / "src" / "desktopMain" / "kotlin" / "app" / "podara" / "util" / "Strings.kt"
OUT = HERE / "charset.txt"
SOURCES = HERE / "sources"

KOTLIN_SIMPLE_ESCAPES = {
    "t": "\t",
    "b": "\b",
    "n": "\n",
    "r": "\r",
    "'": "'",
    '"': '"',
    "\\": "\\",
    "$": "$",
}

# Curated symbol floors. Chars the source font does not have are simply
# dropped by the subsetter (and reported by it), so erring on the side of
# more here costs nothing.
CJK_PUNCT = (
    "、。〃〈〉《》「」『』【】〔〕〖〗〘〙〚〛〜〝〞〟・"
    "﹐﹑﹔﹕﹖﹗﹙﹚﹛﹜﹝﹞"
)
FULLWIDTH = "".join(chr(c) for c in range(0xFF01, 0xFF5F))  # ！＂＃ … ～
IDEOGRAPHIC_SPACE = "\u3000"
GENERAL_PUNCT = "…—–‘’“”·•‰′″※‼©®™°±×÷≠≈≤≥µ¶§†‡←↑→↓↔↕⇐⇒₩€£¥¢"
# ✕ ✗ ✔ ‽ dropped: Noto Sans CJK SC has no glyphs for them and no string
# uses them — kept out so charset.txt states exactly what ships.
SHAPES = "■□▲△▼▽◆◇○●◎★☆♥♦♣♠✓"

# CJK ideographs (Unified U+4E00-U+9FFF plus Extension A U+3400-U+4DBF) — the
# range the common-character tables live in. Anything outside is file noise.
CJK_IDEOGRAPH_LO = 0x3400
CJK_IDEOGRAPH_HI = 0x9FFF


def decode_kotlin_literal(text: str, start: int) -> tuple[str, int]:
    """Decodes the body of a Kotlin string literal, given the index just after
    the opening quote. Returns (decoded, index just past the closing quote)."""
    out = []
    i = start
    while i < len(text):
        c = text[i]
        if c == '"':
            return "".join(out), i + 1
        if c == "\\":
            i += 1
            if i >= len(text):
                raise ValueError("dangling escape at end of Strings.kt")
            e = text[i]
            if e == "u":
                out.append(chr(int(text[i + 1 : i + 5], 16)))
                i += 5
            else:
                out.append(KOTLIN_SIMPLE_ESCAPES.get(e, e))
                i += 1
        else:
            out.append(c)
            i += 1
    raise ValueError("unterminated string literal")


def map_block_chars(text: str, decl: str) -> set[str]:
    """Every character of every string literal inside
    `private val <decl> = mapOf( ... )`, with comments and nesting skipped."""
    marker = f"private val {decl} = mapOf("
    pos = text.index(marker) + len(marker)

    chars: set[str] = set()
    depth = 1  # the mapOf's opening paren is consumed
    in_line_comment = in_block_comment = False
    while pos < len(text):
        c = text[pos]
        nxt = text[pos + 1] if pos + 1 < len(text) else ""
        if in_line_comment:
            if c == "\n":
                in_line_comment = False
            pos += 1
        elif in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                pos += 2
            else:
                pos += 1
        elif c == '"':
            # decode_kotlin_literal consumes through the closing quote and
            # hands back every decoded character of the literal
            decoded, pos = decode_kotlin_literal(text, pos + 1)
            chars |= set(decoded)
        elif c == "/" and nxt == "/":
            in_line_comment = True
            pos += 2
        elif c == "/" and nxt == "*":
            in_block_comment = True
            pos += 2
        elif c == "(":
            depth += 1
            pos += 1
        elif c == ")":
            depth -= 1
            pos += 1
            if depth == 0:
                return chars
        else:
            pos += 1
    raise ValueError(f"mapOf block for {decl!r} never closed")


def strings_chars() -> set[str]:
    text = STRINGS_KT.read_text(encoding="utf-8")
    chars: set[str] = set()
    for decl in ("en", "zh"):
        chars |= map_block_chars(text, decl)
    return chars


def common_table_chars() -> set[str]:
    chars: set[str] = set()
    files = sorted(SOURCES.glob("common-3500-*.txt"))
    if not files:
        sys.exit(
            f"no source tables under {SOURCES} — download them per the "
            "docstring (or accept the degraded fallback by deleting this check)"
        )
    for f in files:
        t = f.read_text(encoding="utf-8")
        table = {c for c in t if CJK_IDEOGRAPH_LO <= ord(c) <= CJK_IDEOGRAPH_HI}
        print(f"{f.name}: {len(table)} ideographs")
        chars |= table
    return chars


def fallback_chars() -> set[str]:
    ascii_printable = {chr(c) for c in range(0x20, 0x7F)}
    return (
        ascii_printable
        | set(CJK_PUNCT)
        | set(FULLWIDTH)
        | {IDEOGRAPHIC_SPACE}
        | set(GENERAL_PUNCT)
        | set(SHAPES)
    )


def main() -> None:
    from_strings = strings_chars()
    from_tables = common_table_chars()
    floor = fallback_chars()

    all_chars = from_strings | from_tables | floor
    print(
        f"Strings.kt: {len(from_strings)}  common tables: {len(from_tables)}  "
        f"floor: {len(floor)}  total: {len(all_chars)}"
    )

    lines = [
        "# Podara bundled-font character set (shared by noto-sc-400/500/700).",
        "# Regenerate:  python tools/font-subset/build_charset.py",
        "# Consumed by: python tools/font-subset/subset.py",
        "# Sources and provenance: tools/font-subset/README.md",
        "#",
        f"# {len(from_strings)} chars from util/Strings.kt (en + zh mapOf literals)",
        f"# {len(from_tables)} chars from the 3500 common-character tables (sources/*.txt, union)",
        f"# {len(floor)} fallback-floor chars (ASCII printable, CJK punct, fullwidth, symbols)",
        f"# total: {len(all_chars)}",
    ]
    lines += [f"U+{ord(c):04X}" for c in sorted(all_chars, key=ord)]
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(lines)} lines)")


if __name__ == "__main__":
    main()
