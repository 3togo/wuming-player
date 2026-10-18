#!/usr/bin/env python3
"""Render project-authored UI vector glyphs and geometric wallpapers."""
import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'artwork/ui-icons'
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)

def main():
    motifs = json.loads((ART / 'motifs.json').read_text())
    for record in json.loads((ART / 'resources.json').read_text()):
        target = ROOT / record['path']
        old = ET.parse(target).getroot()
        attributes = {k: v for k, v in old.attrib.items() if k.rsplit('}', 1)[-1] not in ['viewportWidth', 'viewportHeight']}
        attributes.update({f'{{{ANDROID}}}viewportWidth': '24', f'{{{ANDROID}}}viewportHeight': '24'})
        vector = ET.Element('vector', attributes)
        for path in [' '.join(motifs[record['motif']])]:
            ET.SubElement(vector, 'path', {f'{{{ANDROID}}}pathData': path,
                f'{{{ANDROID}}}fillColor': '@android:color/transparent',
                f'{{{ANDROID}}}strokeColor': '?attr/colorPrimary' if record.get('strokeColor') else '?attr/colorControlNormal',
                f'{{{ANDROID}}}strokeWidth': '1.8', f'{{{ANDROID}}}strokeLineCap': 'round',
                f'{{{ANDROID}}}strokeLineJoin': 'round'})
        # Distinguish enabled/selected versions without relying on color alone.
        if target.stem.endswith('_on'):
            ET.SubElement(vector, 'path', {f'{{{ANDROID}}}pathData': 'M18 2 H23 V7 H18 Z',
                                          f'{{{ANDROID}}}fillColor': '?attr/colorControlNormal'})
        ET.indent(vector)
        ET.ElementTree(vector).write(target, encoding='utf-8', xml_declaration=True)
    spec = importlib.util.spec_from_file_location('branding', ROOT / 'scripts/generate-branding.py')
    branding = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(branding)
    for index in range(1, 5):
        for variant in ['mobile', 'leanback']:
            branding.raster(f'wallpaper-{index}.svg', ROOT / f'app/src/{variant}/res/drawable-nodpi/wallpaper_{index}.webp', 1920, 1080)
    from PIL import Image
    for density in ['hdpi', 'xhdpi', 'xxhdpi']:
        for name in ['vod', 'live', 'setting', 'keep', 'search', 'push']:
            dest = ROOT / f'app/src/leanback/res/drawable-{density}/ic_home_{name}.png'
            width, height = Image.open(dest).size
            branding.raster(f'home-{name}.svg', dest, width, height)
    import shutil
    shutil.copyfile(ROOT / 'artwork/branding/empty-state-animation.json',
                    ROOT / 'app/src/main/res/raw/box_opening.json')
    import re
    nav_motifs = iter(['search', 'upload', 'settings', 'folder'])
    def web_icon(match):
        name = next(nav_motifs)
        data = ' '.join(motifs[name])
        return ('<svg class="md-nav-icon" viewBox="0 0 24 24" aria-hidden="true">'
                '<path fill="none" stroke="currentColor" stroke-width="1.8" '
                'stroke-linecap="round" stroke-linejoin="round" d="' + data + '"/></svg>')
    html = ROOT / 'app/src/main/assets/index.html'
    html.write_text(re.sub(r'<svg class="md-nav-icon"[^>]*>.*?</svg>', web_icon,
                           html.read_text(), flags=re.S))
    javascript = ROOT / 'app/src/main/assets/js/script.js'
    data = javascript.read_text()
    for symbol, motif, color in [('icDir', 'folder', '%23F5A623'), ('icFile', 'file', '%23717970')]:
        svg = ("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>"
               "<path fill='none' stroke='" + color + "' stroke-width='1.8' "
               "stroke-linecap='round' stroke-linejoin='round' d='" + ' '.join(motifs[motif]) + "'/></svg>")
        replacement = "const " + symbol + " = `data:image/svg+xml," + svg + "`;"
        data = re.sub(r'^const ' + symbol + r' = .*?;$', lambda _: replacement, data, flags=re.M)
    javascript.write_text(data)
    print('Rendered original UI glyphs and four wallpaper designs for both Android variants.')

if __name__ == '__main__':
    main()
