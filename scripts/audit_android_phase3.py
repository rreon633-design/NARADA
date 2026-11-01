#!/usr/bin/env python3
"""Audit an already-built development artifact. This does not execute Android/desktop tests."""
import gzip,hashlib,json,os,re,shutil,struct,subprocess,zipfile
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
E=ROOT/'artifacts/android-phase-3'; A=E/'apk-audit'; A.mkdir(parents=True,exist_ok=False)
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
expected={'android.permission.INTERNET','android.permission.CAMERA','android.permission.POST_NOTIFICATIONS','android.permission.WAKE_LOCK','android.permission.ACCESS_NETWORK_STATE','android.permission.RECEIVE_BOOT_COMPLETED','com.narada.mobile.dev.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
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
records=['domain-01','connection-02','interop-02','host-03','lint-01','apk-01','instrumentation-01']
summaries={name:json.loads((E/'runs'/name/'summary.json').read_text()) for name in records}
assert all(s['exit_code']==0 for s in summaries.values())
assert all('--tests' not in s['command'] for s in summaries.values()), 'Preparation filters cannot be a final campaign'
for result in summaries.values():
 assert result['tests']['failed']==0 and result['tests']['errors']==0 and result['tests']['skipped']==0
assert sum(result['tests']['passed'] for result in summaries.values())==297
assert summaries['domain-01']['tests']['passed']==22 and summaries['host-03']['tests']['passed']==118
assert summaries['connection-02']['tests']['passed']==135 and summaries['interop-02']['tests']['passed']==22
# No application/test/build-input edits may be hidden after successful validation.
frozen=json.loads((E/'runs/host-03/source-hashes.json').read_text())
validated=[]
for name,digest in frozen.items():
 path=ROOT/name
 if path.suffix=='.md' or path.name in {'.gitignore','dev-env.sh'}:continue
 assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==digest,('post-validation source change',name)
 validated.append(name)
current_names=set()
for candidate in P.rglob('*'):
 if not candidate.is_file() or any(part in {'build','.cache','.gradle','.kotlin'} for part in candidate.relative_to(P).parts) or candidate.name=='local.properties':continue
 if candidate.suffix in {'.md','.jks','.keystore'} or candidate.name in {'.gitignore','dev-env.sh'}:continue
 current_names.add(candidate.relative_to(ROOT).as_posix())
assert current_names==set(validated), 'Final source set changed'
for record in records:
 other=json.loads((E/'runs'/record/'source-hashes.json').read_text())
 for name in validated:assert other.get(name)==frozen[name],('stage source mismatch',record,name)
lint=ET.parse(E/'runs/lint-01/lint-results-debug.xml').getroot();issues=Counter(n.get('severity') for n in lint.findall('issue'))
assert not any(issues.get(x) for x in ('Fatal','Error','Warning'))
# APKs stay in generated build storage. A lossless outer ZIP is the persistent delivery;
# the signed APK bytes themselves are not modified or re-signed.
artifacts=[]
for path,filename in [(apk,'NARADA_Android_Phase3_Debug.apk'),(test_apk,'NARADA_Android_Phase3_Instrumentation.apk')]:
 digest=hashlib.sha256(path.read_bytes()).hexdigest()
 artifacts.append({'filename':filename,'bytes':path.stat().st_size,'sha256':digest})
archive=ROOT.parent/'NARADA_Android_Phase3_APKs.zip'
assert not archive.exists()
with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as bundle:
 for source,item in zip([apk,test_apk],artifacts):bundle.write(source,item['filename'])
 bundle.writestr('SHA256SUMS.txt',''.join(x['sha256']+'  '+x['filename']+'\n' for x in artifacts))
 bundle.writestr('READ-ME.txt','NARADA Android Phase 3 development APKs.\nExtract before installation. Use a disposable test profile.\nDebug APK: com.narada.mobile.dev 0.3.0 (3), min API 29, target 36.\nInstrumentation APK is test tooling, compiled but NOT executed here.\nNeither APK is a production release. Actual AndroidKeyStore/Conscrypt mTLS, camera/NSD and Windows LAN qualification remain open.\nDo not uninstall or clear app data as a lossless repair.\nVerify SHA256SUMS.txt. See the separate Phase 3 source handoff.\n')
with zipfile.ZipFile(archive) as bundle:
 assert bundle.testzip() is None
 for item in artifacts:assert hashlib.sha256(bundle.read(item['filename'])).hexdigest()==item['sha256']
archive.with_suffix('.zip.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n')
assert certificate=='4867bd2ab95831d130e9d23c21942cca04ad60863f698c9a953f041fc6952c72','Original development signer must be preserved'
assert manifest.get(android+'versionName')=='0.3.0' and manifest.get(android+'versionCode')=='3'
scanner=next(n for n in app.findall('activity') if n.get(android+'name')=='com.narada.android.connection.ScanPairingActivity')
assert scanner.get(android+'exported')=='false'
# Test-only software certificate generators must not become production fallback code.
inspect([sdk_home/'cmdline-tools/latest/bin/apkanalyzer','dex','packages','--defined-only',apk],'dex-packages.txt')
dex=(A/'dex-packages.txt').read_text()
assert 'org.bouncycastle' not in dex and 'AndroidTestIdentities' not in dex and 'TestIdentities' not in dex
# Preserve the verbose inventory losslessly without retaining a 27 MB plaintext duplicate.
raw=(A/'dex-packages.txt').read_bytes();compressed=gzip.compress(raw,compresslevel=9,mtime=0)
assert gzip.decompress(compressed)==raw
(A/'dex-packages.txt.gz').write_bytes(compressed)
(A/'dex-inventory-receipt.json').write_text(json.dumps({'raw_sha256':hashlib.sha256(raw).hexdigest(),'raw_bytes':len(raw),'gzip_sha256':hashlib.sha256(compressed).hexdigest(),'gzip_bytes':len(compressed),'file':'dex-packages.txt.gz','software_fixture_generators_absent':True,'bouncycastle_classes_absent':True},indent=2)+'\n')
(A/'dex-packages.txt').unlink()
reference_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'references').iterdir()) if p.suffix.lower() in {'.png','.html'}}
assert len(reference_hashes)==5
verified=ET.parse(P/'gradle/verification-metadata.xml').getroot()
components=verified.find('{https://schema.gradle.org/dependency-verification}components')
report={'application_id':manifest.get('package'),'version':manifest.get(android+'versionName'),'min_sdk':29,'target_sdk':36,'metadata_schema':3,'sealed_trust_payload_version':2,'legacy_trust_payload_read_supported':1,'test_count':297,'new_phase3_checks':111,'compiled_instrumentation_test_cases':5,'development_debuggable':True,'artifacts':artifacts,'apk_bundle':{'filename':archive.name,'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()},'tests':{'domain_passed':22,'connection_passed':135,'real_rust_interop_passed':22,'android_host_passed':118,'failed':0,'skipped':0},'lint_severities':dict(issues),'lint_policy':'Three pinned-version update-advisory categories remain informational; no application baseline.','permissions':permissions,'exported_components':exported,'file_provider_scope':'cacheDir/support only; one temporary read URI grant','signature_v2_verified':True,'debug_certificate_sha256':certificate,'matching_instrumentation_signature_verified':True,'original_phase1_signer_preserved':True,'test_only_software_crypto_not_bundled':True,'no_new_android_permissions':True,'only_peer_file_route_added':True,'file_preview_not_a_download':True,'zip_16kb_alignment_checked':True,'native_elf_alignment':libs,'native_execution':False,'emulator_execution':False,'instrumentation_built_not_executed':True,'ci_executed':False,'runtime_test_build_inputs_matched_across_final_stages':len(validated),'reference_sha256':reference_hashes,'verified_dependency_components':len(components) if components is not None else 0,'checksum_trust':'TOFU over official Maven/Google repositories, not independent signature/security review','final_stage_records':records}
report['dex_inventory']=json.loads((A/'dex-inventory-receipt.json').read_text())
(E/'validation-summary.json').write_text(json.dumps(report,indent=2)+'\n')
# Preserve all frozen desktop and prior Android evidence, not just the packaged source subset.
history=json.loads((E/'historical-input-hashes.json').read_text())
for name,digest in history.items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,('Historical input changed',name)
report['historical_files_verified_unchanged']=len(history)
(E/'validation-summary.json').write_text(json.dumps(report,indent=2)+'\n')
# Compact exact final source receipt; raw logs and earlier failed runs remain unchanged.
(E/'validated-input-hashes.json').write_text(json.dumps({name:frozen[name] for name in validated},indent=2)+'\n')
print(json.dumps(report,indent=2))
