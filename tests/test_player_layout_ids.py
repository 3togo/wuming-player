"""Inflated player trees must not let state restoration/findViewById alias another control."""
from collections import Counter
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'app/src'
ANDROID_ID = '{http://schemas.android.com/apk/res/android}id'


def layout_path(flavor, orientation, name):
    for qualifier in ([orientation, 'layout'] if orientation != 'layout' else ['layout']):
        for source in [flavor, 'main']:
            candidate = ROOT / source / 'res' / qualifier / (name + '.xml')
            if candidate.exists():
                return candidate
    return None


def inflated_ids(flavor, orientation, name, root_id=None):
    path = layout_path(flavor, orientation, name)
    if path is None:
        return []
    root = ET.parse(path).getroot()
    result = []

    def visit(node, override=None):
        # Android <tag> elements attach keyed metadata to their parent view;
        # their IDs do not identify views in findViewById or saved view state.
        if node.tag == 'tag':
            return
        if node.tag == 'include':
            layout = node.get('layout', '')
            if layout.startswith('@layout/'):
                result.extend(inflated_ids(flavor, orientation, layout.split('/')[1], node.get(ANDROID_ID)))
            return
        identity = override or node.get(ANDROID_ID)
        if identity:
            result.append(identity.split('/')[-1])
        for child in node:
            visit(child)

    visit(root, root_id)
    return result


class PlayerLayoutIdentityTest(unittest.TestCase):
    def test_metadata_tags_do_not_hide_duplicate_view_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layouts = root / 'mobile/res/layout'
            layouts.mkdir(parents=True)
            (layouts / 'fixture.xml').write_text('''
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                    <TextView android:id="@+id/control">
                        <tag android:id="@id/role" android:value="body" />
                    </TextView>
                    <TextView android:id="@id/control">
                        <tag android:id="@id/role" android:value="caption" />
                    </TextView>
                </LinearLayout>
            ''')
            with patch(__name__ + '.ROOT', root):
                self.assertEqual(['control', 'control'], inflated_ids('mobile', 'layout', 'fixture'))

    def test_nested_player_views_have_unique_ids(self):
        for flavor in ['mobile', 'leanback']:
            for orientation in ['layout', 'layout-land']:
                for name in ['activity_video', 'activity_live', 'activity_cast']:
                    if layout_path(flavor, orientation, name) is None:
                        continue
                    with self.subTest(flavor=flavor, orientation=orientation, layout=name):
                        duplicates = [key for key, count in Counter(inflated_ids(flavor, orientation, name)).items() if count > 1]
                        self.assertEqual([], duplicates)
