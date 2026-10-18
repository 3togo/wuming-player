#!/usr/bin/env python3
import os
import re
import csv
from pathlib import Path

LOG_DIR = Path("./test_logs")
OUT_CSV = Path("./hdr_test_summary.csv")

pattern_codec = re.compile(r"codec.*?(av1|hevc|h264)", re.IGNORECASE)
pattern_unsupported = re.compile(r"UnsupportedVideoException")
pattern_dropped = re.compile(r"droppedFrames\s*=\s*(\d+)")
pattern_native_heap = re.compile(r"Native heap:\s*(\d+)\s*KB")
pattern_color_transfer = re.compile(r"transfer\s*=\s*(\d+)")

def parse_one(log_path: Path):
    name = log_path.stem
    result = {
        "test_name": name,
        "codec": None,
        "unsupported": False,
        "dropped_frames": None,
        "native_heap_kb": None,
        "color_transfer": None,
    }
    text = log_path.read_text(errors="ignore")

    if pattern_unsupported.search(text):
        result["unsupported"] = True

    codec_match = pattern_codec.search(text)
    if codec_match:
        result["codec"] = codec_match.group(1).lower()

    drop_match = pattern_dropped.search(text)
    if drop_match:
        result["dropped_frames"] = int(drop_match.group(1))

    heap_match = pattern_native_heap.search(text)
    if heap_match:
        result["native_heap_kb"] = int(heap_match.group(1))

    ct_match = pattern_color_transfer.search(text)
    if ct_match:
        result["color_transfer"] = int(ct_match.group(1))

    return result

def main():
    rows = []
    for logfile in LOG_DIR.glob("*.log"):
        rows.append(parse_one(logfile))
    if not rows:
        print("No log files found in", LOG_DIR)
        return
    with OUT_CSV.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=rows[0].keys())
        w.writeheader()
        w.writerows(rows)
    print(f"Wrote summary CSV to {OUT_CSV}")
    for r in rows:
        print(r)

if __name__ == "__main__":
    main()
