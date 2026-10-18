#!/usr/bin/env python3
"""Create a print-ready interlaced image for a lenticular lens.

Requires Pillow:
    python3 -m pip install Pillow

Example (two-image flip, 100 x 150 mm, measured 39.8 LPI):
    python3 scripts/lenticular_interlace.py \
        first.png second.png -o lenticular.png \
        --width-mm 100 --height-mm 150 --lpi 39.8 --dpi 600

The lens ridges and generated image strips run vertically by default. Place the
flat side of the lens against the print, with the ridged side facing the viewer.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path
from typing import Sequence

try:
    from PIL import Image, ImageOps
except ImportError as exc:  # pragma: no cover - depends on the local environment
    raise SystemExit(
        "Pillow is required. Install it with: python3 -m pip install Pillow"
    ) from exc


MM_PER_INCH = 25.4


def positive_float(value: str) -> float:
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("must be a positive number")
    return number


def output_pixels(mm: float, dpi: float) -> int:
    return max(1, round(mm / MM_PER_INCH * dpi))


def prepare_frame(image: Image.Image, size: tuple[int, int], fit: str) -> Image.Image:
    """Convert a frame to RGB and resize it to the requested canvas."""
    image = ImageOps.exif_transpose(image).convert("RGB")
    if fit == "stretch":
        return image.resize(size, Image.Resampling.LANCZOS)
    if fit == "contain":
        return ImageOps.pad(
            image, size, Image.Resampling.LANCZOS, color="white", centering=(0.5, 0.5)
        )
    return ImageOps.fit(image, size, Image.Resampling.LANCZOS, centering=(0.5, 0.5))


def make_interlaced(
    frames: Sequence[Image.Image],
    size: tuple[int, int],
    dpi: float,
    lpi: float,
    phase: float,
    reverse: bool,
    horizontal_lenticules: bool,
) -> Image.Image:
    """Interlace frames using pixel-center positions to avoid pitch drift."""
    width, height = size
    axis_length = height if horizontal_lenticules else width
    count = len(frames)

    # A source index for every output column (or row). `phase` is measured in
    # fractions of one lenticule and is useful when registration needs nudging.
    source_indices = []
    for coordinate in range(axis_length):
        inches = (coordinate + 0.5) / dpi
        position_in_lenticule = (inches * lpi + phase) % 1.0
        index = min(count - 1, int(position_in_lenticule * count))
        source_indices.append(count - 1 - index if reverse else index)

    result = Image.new("RGB", size)
    if horizontal_lenticules:
        for y, frame_index in enumerate(source_indices):
            result.paste(frames[frame_index].crop((0, y, width, y + 1)), (0, y))
    else:
        for x, frame_index in enumerate(source_indices):
            result.paste(frames[frame_index].crop((x, 0, x + 1, height)), (x, 0))
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Interlace two or more images for printing behind a lenticular lens."
    )
    parser.add_argument("images", nargs="+", type=Path, help="input images in view order")
    parser.add_argument("-o", "--output", type=Path, required=True, help="output PNG/TIFF")
    parser.add_argument("--width-mm", type=positive_float, required=True)
    parser.add_argument("--height-mm", type=positive_float, required=True)
    parser.add_argument("--lpi", type=positive_float, required=True, help="measured lens LPI")
    parser.add_argument("--dpi", type=positive_float, default=600.0, help="printer DPI (default: 600)")
    parser.add_argument(
        "--fit", choices=("cover", "contain", "stretch"), default="cover",
        help="how inputs fill the output canvas (default: cover)",
    )
    parser.add_argument(
        "--phase", type=float, default=0.0,
        help="registration shift in lenticules, normally between 0 and 1",
    )
    parser.add_argument("--reverse", action="store_true", help="reverse the frame order")
    parser.add_argument(
        "--horizontal-lenticules", action="store_true",
        help="make horizontal strips; default is vertical strips for side-to-side viewing",
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if len(args.images) < 2:
        raise SystemExit("At least two input images are required.")
    missing = [str(path) for path in args.images if not path.is_file()]
    if missing:
        raise SystemExit("Input file not found: " + ", ".join(missing))

    size = (output_pixels(args.width_mm, args.dpi), output_pixels(args.height_mm, args.dpi))
    opened = []
    try:
        for path in args.images:
            with Image.open(path) as image:
                opened.append(prepare_frame(image, size, args.fit))
        result = make_interlaced(
            opened, size, args.dpi, args.lpi, args.phase, args.reverse,
            args.horizontal_lenticules,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        result.save(args.output, dpi=(args.dpi, args.dpi))
    finally:
        for image in opened:
            image.close()

    dots_per_lenticule = args.dpi / args.lpi
    dots_per_view = dots_per_lenticule / len(args.images)
    print(f"Saved {args.output} ({size[0]} x {size[1]} px at {args.dpi:g} DPI)")
    print(f"{dots_per_lenticule:.2f} printer dots/lenticule; {dots_per_view:.2f} dots/view")
    if dots_per_view < 2:
        print("Warning: fewer than 2 printer dots per view; use fewer views or a lower-LPI lens.")


if __name__ == "__main__":
    main()
