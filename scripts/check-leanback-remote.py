#!/usr/bin/env python3
"""Offline focus contract checks for the layouts selected by the leanback flavors.

This checks XML/style reachability and focus feedback, not rendered D-pad geometry,
async loading, playback, third-party websites, or device-specific behavior.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
styles, layouts = {}, {}
for source in ('main', 'leanback'):
    resources = ROOT / 'app/src' / source / 'res'
    for path in (resources / 'values').glob('*.xml'):
        for style in ET.parse(path).getroot().findall('style'):
            styles[style.get('name')] = style
    for path in (resources / 'layout').glob('*.xml'):
        layouts[path.stem] = path


def style_attributes(name, seen=()):
    name = name.removeprefix('@style/')
    if name not in styles or name in seen:
        return {}
    node = styles[name]
    result = style_attributes(node.get('parent', name.rpartition('.')[0]), (*seen, name))
    result.update({ANDROID + item.get('name')[8:]: item.text for item in node
                   if item.get('name', '').startswith('android:')})
    return result


def attributes(node):
    return {**style_attributes(node.get('style', '')), **node.attrib}


def focus_drawable(ref, seen=()):
    if not ref or ref in seen:
        return False
    if 'selectableItemBackground' in ref:
        return True
    if not ref.startswith('@'):
        return False
    kind, _, name = ref[1:].partition('/')
    for source in ('leanback', 'main'):
        path = ROOT / 'app/src' / source / 'res' / kind / (name + '.xml')
        if path.exists():
            text = path.read_text()
            return ('state_focused="true"' in text or '<ripple' in text
                    or any(focus_drawable(child, (*seen, ref))
                           for child in re.findall(r'@(?:drawable|color)/\w+', text)))
    return False


def visible_focus(node):
    attrs = attributes(node)
    if any(focus_drawable(attrs.get(ANDROID + key)) for key in ('background', 'foreground', 'textColor')):
        return True
    # E.g. a channel group or oval poster draws its parent's focus on a child.
    return any(child.get(ANDROID + 'duplicateParentState') == 'true' and visible_focus(child)
               for child in node)


# Listener-backed actions whose clickability is assigned in Java rather than XML.
# These previously rendered correctly but could not be selected using a remote.
REQUIRED = {
    'activity_push': ('code', 'clip'),
    'dialog_device': ('disconnect', 'mode', 'scan', 'refresh'),
    'dialog_timer': ('time1', 'time2', 'time3', 'time4'),
    'dialog_info': ('header', 'url'),
    'dialog_receive': ('frame',),
    'adapter_value': ('text',),
    'activity_cloud_storage': ('back', 'cache', 'exportCredentials', 'importCredentials', 'connectors'),
    'activity_cloud_storage_web': ('back', 'reset', 'auto', 'external'),
    'activity_cloud_storage_cache': ('back',),
    'activity_webdav_connector': ('back', 'add'),
}
# Native/custom widgets draw focus themselves. Fullscreen video intentionally
# uses remote playback controls instead of an outline around the entire picture.
NATIVE = ('EditText', 'GridView', 'RecyclerView', 'Slider', 'Switch', 'Button',
          'Chip', 'TimeBar', 'CustomMic', 'CustomSearchView')
VIDEO_SURFACES = {('activity_cast', 'video'), ('activity_live', 'video')}
errors = []
focus_count = 0
for name, path in sorted(layouts.items()):
    tree = ET.parse(path).getroot()
    ids = {node.get(ANDROID + 'id', '').split('/')[-1]: node for node in tree.iter()}
    for required in REQUIRED.get(name, ()):
        node = ids.get(required)
        if node is None or attributes(node).get(ANDROID + 'focusable') != 'true':
            errors.append(f'{name}: action {required} must be focusable')
    for node in tree.iter():
        attrs = attributes(node)
        identity = attrs.get(ANDROID + 'id', '').split('/')[-1]
        native = any(widget in node.tag for widget in NATIVE)
        if any(attrs.get(ANDROID + key) == 'true' for key in ('clickable', 'longClickable')) and not native and attrs.get(ANDROID + 'focusable') != 'true':
            errors.append(f'{name}: clickable {identity or node.tag} cannot receive focus')
        if node.tag.endswith('EditText') and attrs.get(ANDROID + 'background') and not visible_focus(node):
            errors.append(f'{name}: custom editor background hides focus on {identity}')
        if attrs.get(ANDROID + 'focusable') != 'true':
            continue
        focus_count += 1
        if not native and (name, identity) not in VIDEO_SURFACES and not visible_focus(node):
            errors.append(f'{name}: {identity or node.tag} needs visible focus feedback')

# Inspect listener-backed widgets as well as XML clickability. Bindings can be shared
# across layouts in a file, so only check fields that exist in the effective layout.
listener_count = 0
for source in ('main', 'leanback'):
    for path in (ROOT / 'app/src' / source / 'java').rglob('*'):
        if path.suffix not in ('.java', '.kt'):
            continue
        code = path.read_text()
        if path.stem != 'TvAlertDialogBuilder' and re.search(r'\b(?:com\.google\.android\.material\.dialog\.)?MaterialAlertDialogBuilder\s*\(', code):
            errors.append(f'{path.name}: Material dialogs must install the TV focus contract')
        for binding in set(re.findall(r'\b(\w+)Binding\b', code)):
            name = re.sub(r'(?<!^)(?=[A-Z])', '_', binding).lower()
            if name not in layouts:
                continue
            tree = ET.parse(layouts[name]).getroot()
            ids = {node.get(ANDROID + 'id', '').split('/')[-1]: node for node in tree.iter()}
            for field in set(re.findall(r'(?:mBinding|binding|views)(?:!!|\?)?\s*\.\s*`?(\w+)`?(?:\?)?\s*\.\s*setOn(?:Click|LongClick)Listener', code)):
                node = ids.get(field)
                if node is None:
                    continue
                listener_count += 1
                attrs = attributes(node)
                native = any(widget in node.tag for widget in NATIVE)
                if not native and attrs.get(ANDROID + 'focusable') != 'true':
                    errors.append(f'{name}: action {field} cannot receive remote focus')

for name in ('dialog_alistdrive', 'dialog_webdav', 'dialog_webdav_connector', 'dialog_danmaku_search'):
    for node in ET.parse(layouts[name]).getroot().iter():
        if node.tag.endswith('EditText'):
            # The package namespace can change independently of this behavioral
            # contract.  CustomEditText releases Up/Down (and edge Left/Right)
            # to focusSearch; a plain EditText can trap TV remote focus.
            custom_editor = node.tag.rsplit('.', 1)[-1] == 'CustomEditText'
            if not custom_editor or node.get(ANDROID + 'singleLine') != 'true':
                errors.append(f'{name}: form editor must allow D-pad escape')
web = ET.parse(layouts['activity_cloud_storage_web']).getroot()
if not any(node.tag.endswith('.RemoteWebView') for node in web.iter()):
    errors.append('Cloud web page must expose the TV remote pointer')
for locale in ('values', 'values-zh-rCN', 'values-zh-rTW'):
    names = []
    for source in ('main', 'verification'):
        root = ET.parse(ROOT / 'app/src' / source / 'res' / locale / 'strings.xml').getroot()
        names.append(next(node.text for node in root.findall('string') if node.get('name') == 'app_name'))
    if names != ['無名', '無名(testing)']:
        errors.append(f'{locale}: normal and testing app labels must remain distinct')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'PASS: {len(layouts)} effective layouts, {focus_count} explicit focus targets, '
      f'{sum(map(len, REQUIRED.values()))} action contracts, {listener_count} Java/Kotlin listener checks, TV forms/web pointer, and 3 app-label locales.')
print('Device navigation and external website behavior still require runtime testing.')
