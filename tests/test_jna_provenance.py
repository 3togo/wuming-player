import hashlib
import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class JnaProvenanceTest(unittest.TestCase):
    def test_shipped_desktop_jna_has_reviewed_license_and_packaged_notices(self):
        coordinate = re.search(r"implementation ['\"](net\.java\.dev\.jna:jna:[^'\"]+)['\"]",
                               (ROOT / 'desktop/build.gradle').read_text()).group(1)
        inventory = json.loads((ROOT / 'docs/public-maven-inventory.json').read_text())
        records = [r for r in inventory['components'] if r['coordinate'] == coordinate]
        self.assertEqual(1, len(records), 'Desktop JNA must be reviewed in the runtime inventory')
        record = records[0]
        self.assertEqual([':desktop:runtimeClasspath'], record['scopes'])
        self.assertEqual('Apache-2.0', record['selectedLicense'])
        self.assertEqual({'Apache-2.0', 'LGPL-2.1-or-later'},
                         {license['name'] for license in record['publisherLicenses']})
        notices = {entry['file']: entry['sha256'] for entry in record['notices']}
        for name in ('LICENSE', 'NOTICE.txt', 'Apache-2.0.txt'):
            path = 'desktop/src/main/resources/third-party/jna/' + name
            self.assertIn(path, notices)
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), notices[path])
        self.assertIn('Apache License', (ROOT / 'desktop/src/main/resources/third-party/jna/LICENSE').read_text())

    def test_reviewed_inventory_is_bound_to_the_public_provenance_manifest(self):
        manifest = json.loads((ROOT / 'docs/public-provenance-manifest.json').read_text())
        record = next(r for r in manifest['supportingArtifacts'] if r['path'] == 'docs/public-maven-inventory.json')
        self.assertEqual(hashlib.sha256((ROOT / record['path']).read_bytes()).hexdigest(), record['sha256'])
