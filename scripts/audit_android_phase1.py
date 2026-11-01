#!/usr/bin/env python3
"""Audit an already-built development artifact. This does not execute Android/desktop tests."""
import hashlib,json,os,re,shutil,struct,subprocess,zipfile
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
E=ROOT/'artifacts/android-phase-1'; A=E/'apk-audit'; A.mkdir(parents=True,exist_ok=True)
P=ROOT/'apps/android'; apk=P/'app/build/outputs/apk/debug/app-debug.apk'; test_apk=P/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
# Always derive inspection evidence from the APK being audited, never from stale reports.
env=os.environ.copy();tools=Path(env.get('NARADA_ANDROID_TOOLS',str(Path.home()/'.cache/narada-android-tools')))
sdk_home=Path(env.get('ANDROID_HOME',str(tools/'sdk')))
if not env.get('JAVA_HOME') and (tools/'java-home.txt').exists():env['JAVA_HOME']=(tools/'java-home.txt').read_text().strip()
def inspect(command,filename):
 result=subprocess.run([str(x) for x in command],env=env,capture_output=True,text=True)
 (A/filename).write_text(result.stdout+result.stderr)
 if result.returncode:raise RuntimeError(f"Artifact inspection failed: {filename}; see preserved output")
inspect([sdk_home/'cmdline-tools/latest/bin/apkanalyzer','manifest','print',apk],'AndroidManifest.xml')
inspect([sdk_home/'build-tools/35.0.0/apksigner','verify','--verbose','--print-certs',apk],'signature.txt')
inspect([sdk_home/'build-tools/35.0.0/apksigner','verify','--verbose','--print-certs',test_apk],'instrumentation-signature.txt')
inspect([sdk_home/'build-tools/35.0.0/zipalign','-c','-P','16','-v','4',apk],'zipalign.txt')
android='{http://schemas.android.com/apk/res/android}'
manifest=ET.parse(A/'AndroidManifest.xml').getroot(); app=manifest.find('application'); sdk=manifest.find('uses-sdk')
assert manifest.get('package')=='com.narada.mobile.dev'
assert sdk.get(android+'minSdkVersion')=='29' and sdk.get(android+'targetSdkVersion')=='36'
assert app.get(android+'allowBackup')=='false' and app.get(android+'usesCleartextTraffic')=='false'
assert app.get(android+'debuggable')=='true'
permissions=sorted(e.get(android+'name') for e in manifest.findall('uses-permission'))
expected={'android.permission.POST_NOTIFICATIONS','android.permission.WAKE_LOCK','android.permission.ACCESS_NETWORK_STATE','android.permission.RECEIVE_BOOT_COMPLETED','com.narada.mobile.dev.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
assert set(permissions)==expected, permissions
exported=[]
for component in app:
 if component.get(android+'exported')=='true':
  name=component.get(android+'name');perm=component.get(android+'permission')
  assert name=='com.narada.android.MainActivity' or perm in {'android.permission.BIND_JOB_SERVICE','android.permission.DUMP'},(name,perm)
  exported.append({'kind':component.tag,'name':name,'permission':perm})
assert not any('PreviewActivity' in (n.get(android+'name') or '') or 'SystemForegroundService' in (n.get(android+'name') or '') for n in app)
provider=next(n for n in app.findall('provider') if n.get(android+'name')=='androidx.core.content.FileProvider')
assert provider.get(android+'exported')=='false' and provider.get(android+'grantUriPermissions')=='true'
paths=ET.parse(P/'app/src/main/res/xml/support_paths.xml').getroot()
assert len(paths)==1 and paths[0].tag=='cache-path' and paths[0].get('path')=='support/'
signature=(A/'signature.txt').read_text();assert 'Verifies' in signature and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
certificate=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',signature).group(1)
assert 'Verifies' in (A/'instrumentation-signature.txt').read_text()
assert certificate==re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',(A/'instrumentation-signature.txt').read_text()).group(1)
assert any(word in (A/'zipalign.txt').read_text() for word in ('Verification successful', 'Verification succesful'))
libs=[]
with zipfile.ZipFile(apk) as archive:
 assert archive.testzip() is None
 for name in archive.namelist():
  if not name.endswith('.so'):continue
  data=archive.read(name);assert data[:4]==b'\x7fELF'
  bits=64 if data[4]==2 else 32;order='<' if data[5]==1 else '>'
  phoff=struct.unpack_from(order+('Q' if bits==64 else 'I'),data,32 if bits==64 else 28)[0]
  entry=struct.unpack_from(order+'H',data,54 if bits==64 else 42)[0];count=struct.unpack_from(order+'H',data,56 if bits==64 else 44)[0]
  alignments=[]
  for i in range(count):
   offset=phoff+i*entry
   if struct.unpack_from(order+'I',data,offset)[0]==1:alignments.append(struct.unpack_from(order+('Q' if bits==64 else 'I'),data,offset+(48 if bits==64 else 28))[0])
  if bits==64:assert all(n>=16384 for n in alignments),(name,alignments)
  libs.append({'path':name,'bits':bits,'load_alignment_bytes':alignments,'sha256':hashlib.sha256(data).hexdigest()})
records=['domain-02','host-05','lint-02','apk-01','instrumentation-01']
summaries={name:json.loads((E/'runs'/name/'summary.json').read_text()) for name in records}
assert all(s['exit_code']==0 for s in summaries.values())
assert summaries['domain-02']['tests']['passed']==22 and summaries['host-05']['tests']['passed']==60
# No application/test/build-input edits may be hidden after successful validation.
frozen=json.loads((E/'runs/host-05/source-hashes.json').read_text())
validated=[]
for name,digest in frozen.items():
 path=ROOT/name
 if path.suffix=='.md' or path.name in {'.gitignore','dev-env.sh'}:continue
 assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==digest,('post-validation source change',name)
 validated.append(name)
for record in records:
 other=json.loads((E/'runs'/record/'source-hashes.json').read_text())
 for name in validated:assert other.get(name)==frozen[name],('stage source mismatch',record,name)
lint=ET.parse(E/'runs/lint-02/lint-results-debug.xml').getroot();issues=Counter(n.get('severity') for n in lint.findall('issue'))
assert not any(issues.get(x) for x in ('Fatal','Error','Warning'))
artifacts=[]
for path,filename in [(apk,'NARADA_Android_Phase1_Debug.apk'),(test_apk,'NARADA_Android_Phase1_Instrumentation.apk')]:
 destination=ROOT.parent/filename;shutil.copy2(path,destination)
 digest=hashlib.sha256(destination.read_bytes()).hexdigest();destination.with_suffix(destination.suffix+'.sha256').write_text(f'{digest}  {filename}\n')
 artifacts.append({'filename':filename,'bytes':destination.stat().st_size,'sha256':digest})
reference_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'references').iterdir()) if p.suffix.lower() in {'.png','.html'}}
assert len(reference_hashes)==5
verified=ET.parse(P/'gradle/verification-metadata.xml').getroot()
components=verified.find('{https://schema.gradle.org/dependency-verification}components')
report={'application_id':manifest.get('package'),'version':manifest.get(android+'versionName'),'min_sdk':29,'target_sdk':36,'metadata_schema':2,'development_debuggable':True,'artifacts':artifacts,'tests':{'domain_passed':22,'android_host_passed':60,'failed':0,'skipped':0},'lint_severities':dict(issues),'lint_policy':'Three pinned-version update-advisory categories remain informational; no application baseline.','permissions':permissions,'exported_components':exported,'file_provider_scope':'cacheDir/support only; one temporary read URI grant','signature_v2_verified':True,'debug_certificate_sha256':certificate,'matching_instrumentation_signature_verified':True,'zip_16kb_alignment_checked':True,'native_elf_alignment':libs,'native_execution':False,'emulator_execution':False,'instrumentation_built_not_executed':True,'ci_executed':False,'runtime_test_build_inputs_matched_across_final_stages':len(validated),'reference_sha256':reference_hashes,'verified_dependency_components':len(components) if components is not None else 0,'checksum_trust':'TOFU over official Maven/Google repositories, not independent signature/security review','final_stage_records':records}
(E/'validation-summary.json').write_text(json.dumps(report,indent=2)+'\n')
# Compact exact final source receipt; raw logs and earlier failed runs remain unchanged.
(E/'validated-input-hashes.json').write_text(json.dumps({name:frozen[name] for name in validated},indent=2)+'\n')
print(json.dumps(report,indent=2))
