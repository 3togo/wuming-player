#!/usr/bin/env python3
"""Render original wuming-player vector branding; no downloaded image inputs.

Requires Python 3, PyGObject/Rsvg, pycairo and Pillow. Run from any directory.
"""
from pathlib import Path
import io
import xml.etree.ElementTree as ET
import cairo
import gi
from PIL import Image

gi.require_version('Rsvg', '2.0')
from gi.repository import Rsvg

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'artwork/branding'
RES = ROOT / 'app/src/main/res'
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)


def raster(source, dest, width, height):
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, width, height)
    context = cairo.Context(surface)
    viewport = Rsvg.Rectangle()
    viewport.x, viewport.y, viewport.width, viewport.height = 0, 0, width, height
    Rsvg.Handle.new_from_file(str(ART / source)).render_document(context, viewport)
    stream = io.BytesIO()
    surface.write_to_png(stream)
    im = Image.open(stream)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.suffix == '.webp':
        im.save(dest, lossless=True, exact=True)
    elif dest.suffix == '.ico':
        im.save(dest, sizes=[(16, 16), (32, 32), (48, 48)])
    else:
        im.save(dest)


def append_path(parent, path):
    attrs = {f'{{{ANDROID}}}fillColor': path.get('fill', '#00000000'),
             f'{{{ANDROID}}}pathData': path.attrib['d']}
    for svg_name, android_name in [('stroke', 'strokeColor'),
            ('stroke-width', 'strokeWidth'), ('stroke-linecap', 'strokeLineCap'),
            ('stroke-linejoin', 'strokeLineJoin')]:
        if svg_name in path.attrib:
            attrs[f'{{{ANDROID}}}{android_name}'] = path.attrib[svg_name]
    ET.SubElement(parent, 'path', attrs)


def vector(source, dest, width, height):
    svg = ET.parse(ART / source).getroot()
    _, _, vw, vh = svg.attrib['viewBox'].split()
    root = ET.Element('vector', {f'{{{ANDROID}}}width': width,
        f'{{{ANDROID}}}height': height, f'{{{ANDROID}}}viewportWidth': vw,
        f'{{{ANDROID}}}viewportHeight': vh})
    for p in svg.findall('{http://www.w3.org/2000/svg}path'):
        append_path(root, p)
    dest.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root)
    ET.ElementTree(root).write(dest, encoding='utf-8', xml_declaration=True)


def edition_branding():
    """Keep the W and replace only its accent, using shared editable SVG sources."""
    for edition, symbol in [('public', 'edition-globe.svg'), ('private', 'edition-bird.svg')]:
        for source, dest, size, x, y, edge in [
            ('foreground.svg', RES / f'drawable/ic_launcher_symbol_{edition}.xml',
             ('108dp', '108dp'), 69, 23, 13),
            ('banner.svg', ROOT / f'app/src/leanback/res/drawable/ic_banner_{edition}.xml',
             ('320dp', '180dp'), 183, 45, 19),
        ]:
            svg = ET.parse(ART / source).getroot()
            mark = ET.parse(ART / symbol).getroot()
            _, _, width, height = svg.attrib['viewBox'].split()
            _, _, mark_width, mark_height = map(float, mark.attrib['viewBox'].split())
            root = ET.Element('vector', {f'{{{ANDROID}}}width': size[0],
                f'{{{ANDROID}}}height': size[1], f'{{{ANDROID}}}viewportWidth': width,
                f'{{{ANDROID}}}viewportHeight': height})

            # The last path in each original is the small accent above the W.
            for path in svg.findall('{http://www.w3.org/2000/svg}path')[:-1]:
                append_path(root, path)
            scale = str(edge / max(mark_width, mark_height))
            group = ET.SubElement(root, 'group', {f'{{{ANDROID}}}translateX': str(x),
                f'{{{ANDROID}}}translateY': str(y), f'{{{ANDROID}}}scaleX': scale,
                f'{{{ANDROID}}}scaleY': scale})
            for path in mark.findall('{http://www.w3.org/2000/svg}path'):
                append_path(group, path)
            dest.parent.mkdir(parents=True, exist_ok=True)
            ET.indent(root)
            ET.ElementTree(root).write(dest, encoding='utf-8', xml_declaration=True)


def main():
    for density, edge in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96),
                          ('xxhdpi', 144), ('xxxhdpi', 192)]:
        for name, source in [('ic_launcher', 'launcher.svg'),
                             ('ic_launcher_round', 'launcher-round.svg')]:
            raster(source, RES / f'mipmap-{density}/{name}.webp', edge, edge)
        # Foregrounds are now a single scalable drawable rather than five rasters.
        (RES / f'drawable-{density}/ic_launcher_foreground.webp').unlink(missing_ok=True)
        (RES / f'drawable-{density}/ic_notification.webp').unlink(missing_ok=True)
    raster('launcher.svg', ROOT / 'app/src/main/ic_launcher-playstore.png', 512, 512)
    raster('launcher.svg', RES / 'drawable-nodpi/ic_logo.webp', 600, 600)
    raster('launcher.svg', ROOT / 'app/src/main/assets/favicon.ico', 48, 48)
    raster('banner.svg', ROOT / 'app/src/leanback/res/drawable/ic_banner.png', 320, 180)
    raster('banner.svg', RES / 'drawable-nodpi/artwork.webp', 1920, 1080)
    raster('launcher.svg', RES / 'drawable/push_image.webp', 240, 240)
    vector('foreground.svg', RES / 'drawable/ic_launcher_foreground.xml', '108dp', '108dp')
    vector('monochrome.svg', RES / 'drawable/ic_launcher_monochrome.xml', '108dp', '108dp')
    vector('notification.svg', RES / 'drawable-anydpi/ic_notification.xml', '24dp', '24dp')
    # Full rectangular TV banner avoids adaptive square masking and distortion.
    (ROOT / 'app/src/leanback/res/mipmap-anydpi-v26/ic_banner.xml').unlink(missing_ok=True)
    (ROOT / 'app/src/leanback/res/drawable/ic_banner_foreground.xml').unlink(missing_ok=True)
    manifest = ROOT / 'app/src/leanback/AndroidManifest.xml'
    manifest.write_text(manifest.read_text().replace('@mipmap/ic_banner', '@drawable/ic_banner'))
    for name in ['ic_launcher.xml', 'ic_launcher_round.xml']:
        path = RES / 'mipmap-anydpi-v26' / name
        path.write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/wuming_brand_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')
    (RES / 'values/branding.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="wuming_brand_background">#142D3A</color>
</resources>
''')


    edition_branding()


if __name__ == '__main__':
    main()
