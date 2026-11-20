#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only verification of the one exported, review-signed release-config APK."""
from pathlib import Path
import argparse,hashlib,json,os,re,struct,subprocess,sys,tempfile,zipfile
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('apk',type=Path)
parser.add_argument('--stage',choices=['7','8','9','9b','9c','9d','10','11'],default='7')
parser.add_argument('--previous-apk',type=Path)
arguments=parser.parse_args()
label=arguments.stage
stage=9 if label in ('9b','9c','9d') else int(label)
apk=arguments.apk.resolve()
version_code,version_name={'7':(100,'1.0.0-native'),'8':(101,'1.1.0-native-stage8'),'9':(102,'1.2.0-native-stage9'),'9b':(103,'1.2.1-native-stage9b'),'9c':(104,'1.2.2-native-stage9c'),'9d':(105,'1.2.3-native-stage9d'),'10':(106,'1.3.0-native-ota'),'11':(107,'1.3.1-native-usb')}[label]
config=Path('/workspace/native-toolchain/paths.json')
paths=json.loads(config.read_text()) if config.exists() else {'sdk':os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'],'java':os.environ['JAVA_HOME']}
sdk=Path(paths['sdk']);bt=sdk/'build-tools/35.0.0';java=Path(paths['java'])/'bin/java'
def run(*args):return subprocess.check_output(list(map(str,args)),text=True,stderr=subprocess.STDOUT)
badging=run(bt/'aapt2','dump','badging',apk)
permissions=run(bt/'aapt2','dump','permissions',apk)
manifest=run(bt/'aapt2','dump','xmltree','--file','AndroidManifest.xml',apk)
assert "name='com.walkmanstudio.nativeplayer'" in badging
assert f"versionCode='{version_code}'" in badging and f"versionName='{version_name}'" in badging
assert "minSdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging
if stage>=10:
 for required in ['android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.REQUEST_INSTALL_PACKAGES','android.permission.POST_NOTIFICATIONS']:assert required in permissions,required
else:assert 'android.permission.INTERNET' not in permissions
if stage>=11:assert 'android.permission.MODIFY_AUDIO_SETTINGS' in permissions
for forbidden in ['android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.READ_MEDIA_AUDIO','android.permission.READ_EXTERNAL_STORAGE','android.permission.WRITE_EXTERNAL_STORAGE']:
 assert forbidden not in permissions,forbidden
def attribute(name):
 match=re.search(r':'+re.escape(name)+r'\([^)]*\)=(.*)',manifest)
 return match.group(1).split(' (Raw:')[0].strip() if match else None
assert attribute('allowBackup')=='false','allowBackup must befalse'
assert attribute('debuggable') in (None,'false'),'Final must not be debuggable'
assert attribute('stopWithTask')=='false','Task swipe must not stop playback service'
assert 'MediaButtonReceiver' in manifest
assert int(attribute('foregroundServiceType'),0)&2,'FGS must include mediaPlayback'
cert=run(java,'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',apk)
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in cert
certificate=re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',cert).group(1)
previous_certificate=None
if stage>=8:
 assert arguments.previous_apk is not None,'This stage requires its delivered reference APK for an update compatibility check'
 if label=='9':assert hashlib.sha256(arguments.previous_apk.read_bytes()).hexdigest()=='0353c6709caee8039d84b9f97cacffcfe24f2bb3e0a54d2408c0dab0c01e9139','Reference must be the exact delivered walkman-native-final.apk'
 previous_badging=run(bt/'aapt2','dump','badging',arguments.previous_apk)
 assert "name='com.walkmanstudio.nativeplayer'" in previous_badging
 previous_code=int(re.search(r"versionCode='(\d+)'",previous_badging).group(1))
 assert previous_code==({'9b':102,'9c':103,'9d':104,'10':105,'11':106}.get(label,100)) and version_code>previous_code
 if label in ('9b','9c','9d','10','11'):assert version_code==previous_code+1
 previous_cert=run(java,'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',arguments.previous_apk)
 assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in previous_cert
 previous_certificate=re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',previous_cert).group(1)
 assert certificate==previous_certificate,'Different signing certificates cannot update the installed APK'
 if label in ('9b','9c','9d','10','11'):
  assert certificate=='2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2',f'STOP: stage{label} signer is not the required existing certificate'
  reference_sha={'9b':'3937ac256d611aad0829a767763ebbc154c33aa6a07b8e92909128f7f9b33be6','9c':'1f7c0c0f4e6b3654127dbbbcdedd6422c1ce815c5154cf1bf4a395ef8126de33','9d':'66bd2b942c0ccdf6a76932316fca9e22880af6c24515ad96a5989ea20442fb82','10':'f81fe603fed3774200b10635ebc4308aa4e0e52908f8f8c5bd26a4f8fe29579b','11':'81bba42e781720dd6bee4e88c702061ade958806cfc7a35a14fb091dc4411737'}[label]
  reference_stage={'9b':'9','9c':'9b','9d':'9c','10':'9d','11':'10'}[label]
  assert hashlib.sha256(arguments.previous_apk.read_bytes()).hexdigest()==reference_sha,f'Reference must be the delivered stage{reference_stage} APK'
alignment=run(bt/'zipalign','-c','-P','16','-v','4',apk)
assert 'Verification successful' in alignment or 'Verification succesful' in alignment # BuildTools35 spelling
readelf=sdk/'ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
def native_methods(data,descriptor):
 def u4(at):return struct.unpack_from('<I',data,at)[0]
 def uleb(at):
  value=0;shift=0
  while True:
   byte=data[at];at+=1;value|=(byte&127)<<shift
   if byte<128:return value,at
   shift+=7
 def string(index):
  at=u4(u4(0x3c)+index*4);_,at=uleb(at);end=data.index(0,at)
  return data[at:end].decode('utf-8',errors='replace')
 types=[string(u4(u4(0x44)+i*4)) for i in range(u4(0x40))]
 if descriptor not in types:return set()
 cls=types.index(descriptor);class_data=None
 for i in range(u4(0x60)):
  at=u4(0x64)+i*32
  if u4(at)==cls:class_data=u4(at+24);break
 assert class_data,'JNI class must have a DEX definition'
 at=class_data;counts=[]
 for _ in range(4):value,at=uleb(at);counts.append(value)
 for _ in range(counts[0]+counts[1]):_,at=uleb(at);_,at=uleb(at)
 names=set()
 for count in counts[2:]:
  method_index=0
  for _ in range(count):
   delta,at=uleb(at);method_index+=delta;flags,at=uleb(at);code,at=uleb(at)
   if flags&0x100:
    assert code==0,'Native DEX method cannot have Java bytecode'
    name_index=u4(u4(0x5c)+method_index*8+4);names.add(string(name_index))
 return names
loads={};jni_methods=set()
with zipfile.ZipFile(apk) as archive,tempfile.TemporaryDirectory(prefix='walkman-elf-') as temporary:
 names=archive.namelist()
 for name in names:
  if re.fullmatch(r'classes\d*\.dex',name):jni_methods.update(native_methods(archive.read(name),'Lcom/walkmanstudio/nativeplayer/playback/dsd/NativeDsdFir;'))
 assert {'create','decodeNative','destroy'}<=jni_methods,jni_methods
 abis={p.split('/')[1] for p in names if p.startswith('lib/') and p.endswith('.so')}
 assert abis=={'arm64-v8a','x86_64'},abis
 for abi in abis:assert f'lib/{abi}/libwalkman_dsd.so' in names
 assert not any(p.endswith(('.jks','.keystore','.p12','.wasm','.mp3','.flac','.dsf','.dff')) for p in names)
 for name in names:
  if not name.startswith('lib/') or not name.endswith('.so'):continue
  file=Path(temporary)/name.replace('/','_');file.write_bytes(archive.read(name))
  program=run(readelf,'-lW',file)
  values=[int(line.split()[-1],16) for line in program.splitlines() if line.strip().startswith('LOAD ')]
  assert values and min(values)>=16384,(name,values)
  loads[name]=values
  if name.endswith('/libwalkman_dsd.so'):
   symbols=run(readelf,'--dyn-syms',file)
   for method in ['create','decodeNative','destroy']:assert 'Java_com_walkmanstudio_nativeplayer_playback_dsd_NativeDsdFir_'+method in symbols,'JNI export missing'
mapping=(root/'app/build/outputs/mapping/review/mapping.txt').read_text()
cls='com.walkmanstudio.nativeplayer.playback.dsd.NativeDsdFir'
assert f'{cls} -> {cls}:' in mapping,'JNI class renamed'
# Unchanged native methods are omitted from R8 mapping; DEX declarations and ELF exports are checked above.
report={'apk':apk.name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
 'applicationId':'com.walkmanstudio.nativeplayer','versionName':version_name,'versionCode':version_code,'minSdk':26,'targetSdk':35,
 'variant':'review (release/R8 config, debuggable=false)','signature':'existing build-environment install/test key; NOT user release key',
 'certificateSha256':certificate,'previousCertificateSha256':previous_certificate,'updatesDeliveredStage7Signer':stage>=8,
 'apkV2Verified':True,'zip16KiBVerified':True,'elfLoadAlignments':loads,
 'forbiddenPermissionsAbsent':True,'nativeJniNamePreserved':True,'nativeMethodsVerified':sorted(jni_methods),
 'notVerified':['installation/audio on physical Android','user keystore signing','16KiB-kernel execution']}
(root/(f'qa/stage{arguments.stage}-apk.json' if stage>=8 else 'qa/final-apk.json')).write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
