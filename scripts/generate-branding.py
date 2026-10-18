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


def vector(source, dest, width, height):
    svg = ET.parse(ART / source).getroot()
    _, _, vw, vh = svg.attrib['viewBox'].split()
    root = ET.Element('vector', {f'{{{ANDROID}}}width': width,
        f'{{{ANDROID}}}height': height, f'{{{ANDROID}}}viewportWidth': vw,
        f'{{{ANDROID}}}viewportHeight': vh})
    for p in svg.findall('{http://www.w3.org/2000/svg}path'):
        ET.SubElement(root, 'path', {f'{{{ANDROID}}}fillColor': p.attrib['fill'],
                                   f'{{{ANDROID}}}pathData': p.attrib['d']})
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


if __name__ == '__main__':
    main()
