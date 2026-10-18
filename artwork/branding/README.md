# wuming-player branding

These geometric vector designs were authored for wuming-player on 2026-10-18.
They use hand-specified filled polygons, with no downloaded artwork, stock icons,
font glyphs, traced illustrations, or image-generation inputs. They replace the
inherited cat launcher, cube logo/banner/favicon/notification, landscape playback
placeholder, and paper-plane push placeholder whose individual provenance was
not established during public-release preparation.

The design is a folded W ribbon with a separate amber signal tile on a dark blue
background. The monochrome version omits color; the notification retains only the
W for small-scale readability. The adaptive foreground has additional safe-area
padding. The rectangular TV banner is a conventional drawable so the mark keeps
its proportions rather than being processed as a square adaptive icon.

These source SVGs and their generated derivatives are distributed under the
repository's GPL-3.0 license. No exclusive trademark rights or legal clearance
are asserted. This replacement records the origin of the application branding;
UI glyphs, home tiles and wallpapers have also been replaced with original geometry;
the broader catalog/library review is recorded in docs/vendor-provenance-review.md.

## Regeneration

Run `python3 scripts/generate-branding.py`. It requires Python 3, PyGObject with
Rsvg 2, pycairo and Pillow. SVGs are the editable source; the script renders lossless
WebP/PNG and multi-size ICO files and emits Android vector/adaptive resources.
No network access or external image input is used. Desktop packaging already
consumes `app/src/main/ic_launcher-playstore.png`, so it shares this branding.

The application names remain 無名 and 無名(testing).

The empty-state Lottie animation is also project-authored: a hand-specified W
stroke, an amber dot and a two-second scale pulse, with no imported shapes, fonts
or images. Its editable JSON is `empty-state-animation.json`; the UI artwork
generator copies it to the existing raw resource to preserve both layouts.
