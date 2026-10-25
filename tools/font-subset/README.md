# tools/font-subset — the bundled Noto Sans SC pipeline

Feeds DESIGN-CRAFT.md B1: the app ships a real 400/500/700 CJK weight axis
instead of Windows' algorithmic synthetic bold. Everything in
`desktop/src/desktopMain/resources/fonts/` is **generated** — do not hand-edit
the `.otf` files; rerun this pipeline instead.

## Regenerate everything

Prerequisites: Python 3.12+, `pip install fonttools` (4.54.1 generated the
shipped files), `curl` on PATH.

```bash
# 1. Source fonts (~16 MB each, OTTO magic; -L is required — GitHub raw redirects)
mkdir -p ../build/font-src
curl -L -o ../build/font-src/NotoSansCJKsc-Regular.otf \
  https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf
curl -L -o ../build/font-src/NotoSansCJKsc-Medium.otf \
  https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Medium.otf
curl -L -o ../build/font-src/NotoSansCJKsc-Bold.otf \
  https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Bold.otf

# 2. The licence that ships beside the subsets
curl -L -o ../desktop/src/desktopMain/resources/fonts/OFL.txt \
  https://github.com/notofonts/noto-cjk/raw/main/Sans/LICENSE

# 3. Charset, then subsets
python build_charset.py
python subset.py
```

Outputs (all three weights share `charset.txt`):

| file | cut from | budget |
| --- | --- | --- |
| `desktop/src/desktopMain/resources/fonts/noto-sc-400.otf` | `-Regular` | ≤ 4 MB |
| `desktop/src/desktopMain/resources/fonts/noto-sc-500.otf` | `-Medium` | ≤ 4 MB |
| `desktop/src/desktopMain/resources/fonts/noto-sc-700.otf` | `-Bold` | ≤ 4 MB |

`subset.py` exits non-zero past the budget and reports any requested codepoint
the source font lacks. It never trims the character set to fit.

`desktop/src/desktopTest/kotlin/app/podara/desktop/BundledFontCoverageTest.kt`
pins the result: every `Strings.kt` literal character and all printable ASCII
must display in all three weights. Adding UI copy that uses a character the
tables don't cover fails that test — the fix is refreshing the tables under
`sources/` and rerunning this pipeline.

## Character set provenance

`build_charset.py` unions:

1. `sources/common-3500-a.txt` —《常用汉字库 3500》
   <https://raw.githubusercontent.com/kaienfr/Font/master/learnfiles/常用汉字库 3500.txt>
   (3751 unique CJK ideographs as fetched)
2. `sources/common-3500-b.txt` —《3500 常用汉字》
   <https://raw.githubusercontent.com/elephantnose/characters/master/3500常用汉字.txt>
   (exactly 3500 unique CJK ideographs)

Both files claim to represent the 1988《现代汉语常用字表》but the third-party
copies disagree (378 chars only in a, 127 only in b). The union is taken — a
missing glyph is the one risk B1 names, and a few hundred extra characters
stay far under the size budget.

3. Every string literal in `util/Strings.kt`'s `en` and `zh` mapOf blocks
   (decoded through Kotlin escape rules).
4. A floor: ASCII printable, common CJK punctuation, the fullwidth forms block
   U+FF01–U+FF5E, and a small curated symbol set.

The copies under `sources/` are the exact bytes fetched for the shipped
subsets, so the pipeline is reproducible offline. Only the CJK ideograph range
(U+3400–U+9FFF) is taken from the tables.

## Files

| file | role |
| --- | --- |
| `build_charset.py` | builds `charset.txt` from `sources/` + `Strings.kt` + floor |
| `charset.txt` | the shared character set, one `U+XXXX` per line, `#` comments |
| `subset.py` | cuts the three weights with fontTools.subset, keeping all layout features and the `.notdef` outline |
| `sources/` | archived byte-identical copies of the two 3500-character tables |
