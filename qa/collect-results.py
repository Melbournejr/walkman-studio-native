#!/usr/bin/env python3
"""Require real XML results for every module; reject failed or skipped tests."""
import json,sys,xml.etree.ElementTree as E
from pathlib import Path
root=Path(__file__).resolve().parents[1]
label=sys.argv[1];stage=9 if label in ('9b','9c','9d') else int(label);modules=['core','library','playback','app'];out={}
for module in modules:
 variant='test' if module=='core' else 'testDebugUnitTest'
 files=list((root/module/'build/test-results'/variant).glob('TEST-*.xml'))
 if not files:raise SystemExit(f'Missing results: {module}')
 suites=[E.parse(f).getroot() for f in files]
 out[module]={k:sum(int(x.attrib.get(k,0)) for x in suites) for k in ['tests','failures','errors','skipped']}
 if out[module]['tests']<1 or any(out[module][k] for k in ['failures','errors','skipped']):raise SystemExit(str(out))
total=sum(x['tests'] for x in out.values())
if total<69 or (stage==2 and total!=69) or (label=='9b' and total<162) or (label=='9c' and total<202) or (label=='9d' and total<220) or (label=='10' and total<271) or (label=='11' and total<298):raise SystemExit(f'Unexpected total {total}')
(root/f'qa/stage{label}-test-results.json').write_text(json.dumps(out,indent=2)+'\n')
print(f'{total}/{total} tests; zero failures/errors/skips')
