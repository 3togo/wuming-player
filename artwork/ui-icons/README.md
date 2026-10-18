# Original UI glyphs

The path commands in motifs.json were hand-authored for wuming-player on 2026-10-18
from basic lines, arcs, rectangles and polygons. They replace inherited UI vectors
whose individual artwork origins were not recorded. No inherited path geometry,
font glyph, stock icon or provider logo is used. resources.json maps each UI resource
to its motif. The Android width, height and tint attributes are retained to preserve
layout/theme behavior. Enabled variants add a solid indicator as well as color.

Run `python3 scripts/generate-ui-artwork.py` to regenerate the registered Android
vectors and both Android variants' wallpapers. The four wallpapers are independently
authored geometric SVGs in artwork/branding/wallpaper-*.svg. They replace all inherited
wallpaper bitmaps. No external photographs or illustrations are used.

Source and derivatives use the repository's GPL-3.0 license. Regeneration uses the
same vector-rendering prerequisites as scripts/generate-branding.py.

TV home raster tiles are rendered from `artwork/branding/home-*.svg`, using these
same original glyph motifs. Wallpaper sources are `artwork/branding/wallpaper-*.svg`.
No inherited raster geometry or photographic inputs remain in these replacements.
