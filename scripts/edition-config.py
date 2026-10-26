#!/usr/bin/env python3
"""Read the same edition identity used by Gradle and APK/runtime tooling."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def application_id(root=ROOT):
    values = json.loads((root / 'gradle/edition-defaults.json').read_text())
    override = root / 'edition.json'
    if override.is_file():
        values.update(json.loads(override.read_text()))
    return values['applicationId']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verification', action='store_true')
    args = parser.parse_args()
    print(application_id() + ('.verification' if args.verification else ''))
