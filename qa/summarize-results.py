#!/usr/bin/env python3
"""Summarize actual Gradle JUnit XML, fail if missing or unsuccessful."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
modules = {}
for module in ('core', 'library', 'app'):
    files = list((root / module / 'build/test-results').rglob('TEST-*.xml'))
    if not files:
        raise SystemExit(f'Missing JUnit results: {module}')
    suites = [ET.parse(p).getroot() for p in files]
    modules[module] = {
        key: sum(int(s.attrib.get(key, '0')) for s in suites)
        for key in ('tests', 'failures', 'errors', 'skipped')
    }
    modules[module]['seconds'] = round(sum(float(s.attrib.get('time', '0')) for s in suites), 3)
report = {'modules': modules, 'totalTests': sum(m['tests'] for m in modules.values()),
          'environment': 'Host JVM; library/startup tests use Robolectric API 35, not a physical phone'}
(root / 'qa/test-results.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
if any(m['failures'] or m['errors'] or m['skipped'] for m in modules.values()):
    raise SystemExit('Results contain failures/errors/skipped tests')
