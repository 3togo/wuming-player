#!/usr/bin/env python3
"""Summarize module JUnit reports without copying test output or private diagnostics."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(root):
    rows = []
    for path in sorted(root.glob('**/build/test-results/**/TEST-*.xml')):
        if 'isolated' in path.relative_to(root).parts:
            continue
        suite = ET.parse(path).getroot()
        rows.append({'report': str(path.relative_to(root)),
                     **{key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--acceptance-outcome', default='unknown')
    args = parser.parse_args()
    rows = summarize(args.root)
    destination = args.root / 'build/reports/test-summary'
    destination.mkdir(parents=True, exist_ok=True)
    (destination / 'summary.json').write_text(json.dumps({'acceptance': args.acceptance_outcome, 'suites': rows}, indent=2) + '\n')
    lines = ['# Test evidence', '', f'Acceptance invocation: **{args.acceptance_outcome}**.', '',
             'Counts reflect available JUnit reports; missing reports are not passes. Cached reports may predate this invocation.', '',
             '| Report | Tests | Failures | Errors | Skipped |', '| --- | --- | --- | --- | --- |']
    for row in rows:
        lines.append('| ' + ' | '.join(str(row[key]) for key in ('report', 'tests', 'failures', 'errors', 'skipped')) + ' |')
    (destination / 'summary.md').write_text('\n'.join(lines) + '\n')

if __name__ == '__main__':
    main()
