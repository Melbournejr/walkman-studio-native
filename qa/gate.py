#!/usr/bin/env python3
"""Strict per-stage validation. A failed command ends the gate; never advance/tag."""
from pathlib import Path
import json,os,subprocess,sys,time
root=Path(__file__).resolve().parents[1]
label=sys.argv[1]; stage=9 if label in ('9b','9c','9d') else int(label); assert 2<=stage<=10
modules=['core','library','playback','app']
variant='Review' if stage==2 else 'Release' if stage>=7 else 'Debug'
library_variant='Release' if stage==2 or stage>=7 else 'Debug'
tasks=([":playback:hostNative"] if stage>=6 else [])+[f':app:assemble{variant}']+[f':{name}:test' if name=='core' else f':{name}:testDebugUnitTest' for name in modules]
if stage>=7:tasks += [':app:assembleReview',':app:lintReview']
tasks += [f':app:lint{variant}',f':library:lint{library_variant}',f':playback:lint{library_variant}']
cloud=root/'qa/cloud-gradle.py'
prefix=[sys.executable,str(cloud)] if cloud.exists() else [str(root/'gradlew'),'--console=plain']
log=root/f'qa/stage{label}-gate.log'
start=time.time()
offline=['--offline'] if '--offline' in sys.argv[2:] else []
with log.open('w') as out:
 result=subprocess.run(prefix+tasks+offline+['-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2'],cwd=root,stdout=out,stderr=subprocess.STDOUT)
if result.returncode == 0:
 verification=subprocess.run([sys.executable,str(root/'qa/collect-results.py'),label],cwd=root)
 if verification.returncode:sys.exit(verification.returncode)
report={'stage':label,'offline':bool(offline),'command':tasks,'exitCode':result.returncode,'elapsedSeconds':round(time.time()-start,3),'log':log.name}
(root/f'qa/stage{label}-gate.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
if result.returncode:print('STOP: build/test/lint gate failed. Do not advance or create a success tag.')
sys.exit(result.returncode)
