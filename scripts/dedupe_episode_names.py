#!/usr/bin/env python3
"""Episode-name de-redundancy rules derived from the 饭太硬 scan.

Applies the pipeline agreed from analyzing desktop/episode_names_long.txt:
  1. drop file extension and cloud-drive size prefix
  2. drop copy/rename suffixes like (1) / _2
  3. drop site spam tokens (single-letter CJK prefix, hash IDs, truncation)
  4. drop release-group tail (-Group)
  5. drop technical tokens: resolution / source / codec / audio / subtitle tags
  6. drop year / region / genre blocks (redundant with the detail page)
  7. normalize separators, collapse whitespace
  8. empty result falls back to 正片
Edition words (导演剪辑版/加长版/未删减/语言) are whitelisted by simply never
matching the removal patterns, so versions stay distinguishable.

Usage:
  python3 scripts/dedupe_episode_names.py [report.json] [-o cleaned.txt]
Input defaults to desktop/episode_names_report.json and also accepts the raw
long-name txt (one name per line). Run with --selftest to verify the rules.
"""

import json
import re
import sys
from collections import Counter

FALLBACK = "正片"

# 1) file extension (before anything else)
EXT = re.compile(r"\.(mp4|mkv|avi|ts|m2ts|wmv|rmvb|mov|flv|iso|mpg|mpeg)\s*$", re.I)
# 2) cloud-drive size prefix: [3.34GB] / [891.98MB] / (5.70GB)
SIZE = re.compile(r"^[\[\(（【]?\s*[\d.]+\s*(KB|MB|GB|TB)[\]\)）】]?\s*[.\-_]?\s*", re.I)
# 3) copy / rename suffixes: "xxx (1)", "xxx_2"
COPY_PAREN = re.compile(r"\s*[\(（]\d{1,3}[\)）]\s*$")
COPY_UNDERSCORE = re.compile(r"_\d+$")
# 4) spam: single latin letter glued before CJK ("H哈利波特"), hash IDs, truncation
CJK = r"\u4e00-\u9fff"
SPAM_PREFIX = re.compile(r"^[A-Za-z](?=[" + CJK + r"])")
HASH_ID = re.compile(r"\b[A-Za-z]{1,4}-\d{2,6}-[a-f0-9]{4,}\b", re.I)
ID_FIELD = re.compile(r"\bID\s*[：:]\s*\d+", re.I)
# 6) release-group tail: "...Atmos-FGT" / "...CHS-微博影音臻享" (only trailing)
GROUP_TAIL = re.compile(r"-[\w@" + CJK + r"．]{2,}\s*$")
# year / region / genre blocks (redundant with the detail page)
YEAR_BRACKET = re.compile(r"[\(（【]\s*(19|20)\d{2}\s*[\)）】]")
YEAR_PLAIN = re.compile(r"(?<!\d)(19|20)\d{2}(?!\d)")
EMPTY_BOOK = re.compile(r"《\s*》")
GENRES = ("惊悚片|恐怖片|剧情片|科幻片|动作片|喜剧片|爱情片|战争片|"
          "纪录片|动画片|犯罪片|悬疑片|奇幻片|冒险片")
REGIONS = ("中国大陆|内地|美国|英国|法国|德国|日本|韩国|印度|泰国|澳大利亚|"
           "加拿大|土耳其|西班牙|意大利|俄罗斯")

# 5) technical tokens, removed with latin boundary checks, repeatedly until stable
TECH = [
    # resolution
    r"2160p", r"1080[pi]", r"720p", r"4k", r"uhd",
    # source
    r"blu-ray", r"bluray", r"bd1080p?", r"hd1080p?", r"web-dl", r"web-?leaked",
    r"dvdrip", r"remux", r"itunes", r"viutv", r"hkg", r"hdtv", r"bd\b",
    # codec
    r"x26[45]", r"h\.?26[45]", r"avc", r"hevc", r"10bit",
    r"dv",
    # audio
    r"aac2?\.?0?", r"ac3", r"2ac3", r"ddp\d?(\.5)?\.?1?", r"truehd\d?(\.\d)?",
    r"dts-?hd(\.ma)?", r"atmos", r"flac",
    # subtitle / audio language tags
    r"chs", r"cht", r"che", r"eng", r"big5", r"rus", r"mandarinenglish",
    r"mandarincantonese",
    r"mandarin", r"cantonese", r"japanese", r"english",
    # leak tag / known site watermarks & release groups without a leading dash
    r"hc", r"web", r"bde4", r"hkfact", r"mp4ba", r"movieshaw", r"52zygx",
    r"ffans", r"bucrt", r"dreamhd", r"fgt", r"rarbg",
    # streamer / vendor tags and leftover audio-channel numbers
    r"amzn", r"viu", r"jkyy", r"3audios", r"[57]\.1",
]

# glued variants like "NightKing1080P" / "夜王4k": resolution stuck to a word
GLUED_TECH = [
    re.compile(r"(?i)(?<=[a-z0-9" + CJK + r"])(1080|2160|720)p"),
    re.compile(r"(?i)(?<=[a-z0-9" + CJK + r"])4k"),
]

# Chinese tags: subtitle / watermark / misc quality words (CJK has no \b, use plain match)
CN_TAGS = [
    "特效中英字幕", "中英字幕", "中英双字", "中文字幕",
    "粤语中文字幕", "粤语中字", "粤语繁中", "繁体中字",
    "日语中字", "法语中字", "韩语中字", "英语中字",
    "内封简繁字幕", "简繁字幕", "国英双语", "国英双字", "国粤双语", "中日双语",
    "简繁", "字幕", "中字", "双字", "繁中", "中英", "无水印", "高码率", "内封",
    "双语", "完整版资源", "加长版资源", "微博影音臻享", "微博", "臻享", "德影",
]

# subtitle variants with typos/noise chars: 官方中字 / 官方这中字 / 人工中字
CN_REGEX = [re.compile(r"官方.{0,2}中字"), re.compile(r"人工.{0,2}中字")]


def clean_episode_name(name: str) -> str:
    s = name.strip()
    s = EXT.sub("", s)
    s = SIZE.sub("", s)
    s = COPY_PAREN.sub("", s)
    s = COPY_UNDERSCORE.sub("", s)
    s = SPAM_PREFIX.sub("", s)
    s = HASH_ID.sub("", s)
    s = ID_FIELD.sub("", s)
    s = s.replace("…", " ").replace("...", " ")
    s = GROUP_TAIL.sub("", s)
    s = EMPTY_BOOK.sub("", s)

    # technical tokens: loop until stable because tokens separate each other
    patterns = [re.compile(r"(?<![A-Za-z0-9])" + t + r"(?![A-Za-z0-9])", re.I)
                for t in TECH]
    changed = True
    while changed:
        changed = False
        for p in patterns:
            new = p.sub(" ", s)
            if new != s:
                s, changed = new, True
    for p in GLUED_TECH:
        s = p.sub("", s)
    for p in (YEAR_BRACKET, GENRES, REGIONS, YEAR_PLAIN):
        s = re.sub(p, " ", s)
    for p in CN_REGEX:
        s = p.sub(" ", s)
    for tag in CN_TAGS:
        s = s.replace(tag, " ")

    # normalize separators: dots/brackets become spaces, collapse, trim
    s = re.sub(r"[.\[\]【】《》「」·（）()]+", " ", s)
    s = re.sub(r"\s+", " ", s).strip(" -_.,，、|")
    return s or FALLBACK


def load_long_names(path):
    if path.endswith(".json"):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        samples = data.get("longNameSamples") or []
        return [(e.get("site", ""), e.get("vod", ""), e.get("line", ""), e["name"])
                for e in samples]
    with open(path, encoding="utf-8") as f:
        return [("", "", "", line.strip()) for line in f if line.strip()]


def dedupe_group(cleaned):
    """Within one (site, vod, line), disambiguate collisions with quality/size."""
    counts = Counter(cleaned)
    seen = Counter()
    out = []
    for original, name in cleaned:
        if counts[name] > 1:
            seen[name] += 1
            m = re.search(r"4K|2160p|1080p|720p", original, re.I)
            size = re.search(r"[\d.]+\s?(GB|MB)", original, re.I)
            tag = m.group(0).upper() if m else (size.group(0) if size else f"#{seen[name]}")
            out.append(f"{name} ({tag})")
        else:
            out.append(name)
    return out


def selftest():
    cases = {
        "[3.34GB] 2026《》 The.Last.Blossom.2026.1080p日语中字.mp4": "The Last Blossom",
        "[2.65GB]夜王(导演版).Night.King.Director's.Cut.2026.HD1080P.粤语中字.mp4": "夜王 导演版 Night King Director's Cut",
        "[13.29GB] H哈利波特与死亡圣器下4K国英双语中英字幕2011 Harry.Potter.and.the.Deathly.Hallows.Part.2.2011.2160p.BluRay.x265.AAC.CHS.ENG-微博影音臻享.mp4": "哈利波特与死亡圣器下 Harry Potter and the Deathly Hallows Part 2",
        "[3.45GB]4K.mkv": FALLBACK,
        "[7.49GB] 【2026】【1080P】 打生桩.mp4": "打生桩",
        "[3.55GB] 青蜂侠2011 青蜂侠.1994.DVDRip.2AC3.HKFACT.mkv": "青蜂侠 青蜂侠",
        "第01集": "第01集",
    }
    for raw, expected in cases.items():
        got = clean_episode_name(raw)
        status = "OK " if got == expected else "FAIL"
        print(f"{status}  {raw[:60]!r}\n     -> {got!r}")
        assert got == expected, f"expected {expected!r}"
    print("selftest passed")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    if "--selftest" in sys.argv:
        selftest()
        return
    path = args[0] if args else "desktop/episode_names_report.json"
    out_path = "desktop/episode_names_cleaned.txt"
    if "-o" in sys.argv:
        out_path = sys.argv[sys.argv.index("-o") + 1]

    rows = load_long_names(path)
    if not rows:
        print("no long names found in", path)
        return

    groups = {}
    for site, vod, line, name in rows:
        groups.setdefault((site, vod, line), []).append(name)

    results = []
    for (site, vod, line), names in groups.items():
        cleaned = [(n, clean_episode_name(n)) for n in names]
        deduped = dedupe_group(cleaned)
        for original, final in zip(names, deduped):
            results.append((site, vod, line, original, final))

    with open(out_path, "w", encoding="utf-8") as f:
        for _site, _vod, _line, _original, final in results:
            f.write(final + "\n")

    changed = sum(1 for r in results if r[3] != r[4])
    print(f"cleaned {len(results)} names: {changed} changed, "
          f"{len(results) - changed} unchanged")
    print(f"output: {out_path}\n")
    for site, _v, _l, original, final in results[:30]:
        print(f"[{site}] {original[:52]}\n   -> {final}")


if __name__ == "__main__":
    main()
