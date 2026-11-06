#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import subprocess,os,json,platform,hashlib,statistics
root=Path(__file__).resolve().parents[1];src=root/'playback/src/main/cpp';out=root/'playback/build/native-host';out.mkdir(parents=True,exist_ok=True)
compiler=os.environ.get('CXX','g++');flags=['-std=c++17','-O3','-Wall','-Wextra','-Werror','-ffp-contract=off','-pthread']
java=Path(os.environ['JAVA_HOME']).resolve()
subprocess.run([compiler,*flags,'-shared','-fPIC','-I'+str(java/'include'),'-I'+str(java/'include/linux'),str(src/'dsd_fir.cpp'),str(src/'dsd_jni.cpp'),'-o',str(out/'libwalkman_dsd.so')],check=True)
subprocess.run([compiler,*flags,str(src/'dsd_fir.cpp'),str(src/'dsd_host_test.cpp'),'-o',str(out/'dsd-host-test')],check=True)
samples=[]
for index in range(3):
 sample=out/f'benchmark-{index}.json'
 subprocess.run([str(out/'dsd-host-test'),str(sample)],check=True)
 samples.append(json.loads(sample.read_text()))
p=root/'qa/dsd-host-benchmark.json';report=samples[0];report['timingSamplesPerRate']=3
for index,result in enumerate(report['results']):
 result['decodeMsSamples']=[s['results'][index]['decodeMs'] for s in samples]
 result['rtfSamples']=[s['results'][index]['rtf'] for s in samples]
 result['decodeMs']=statistics.median(result['decodeMsSamples']);result['rtf']=statistics.median(result['rtfSamples'])
report['timingNotes']='Three wall-clock samples per rate; shared host, not a device extrapolation; JNI/SAF IO excluded.'
report['host']=platform.platform();report['compiler']=subprocess.check_output([compiler,'--version'],text=True).splitlines()[0]
report['cpu']=next((line.split(':',1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')),'unknown')
report['sourceSha256']=hashlib.sha256(b''.join((src/n).read_bytes() for n in ['dsd_fir.h','dsd_fir.cpp','dsd_jni.cpp','dsd_host_test.cpp'])).hexdigest()
p.write_text(json.dumps(report,indent=2)+'\n')
