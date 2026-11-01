#!/usr/bin/env python3
"""Audit an already-built development artifact. This does not execute Android/desktop tests."""
import hashlib,io,json,lzma,os,re,struct,subprocess,tarfile,zipfile
from android_phase4_history import verify_historical
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
E=ROOT/'artifacts/android-phase-4'; A=E/'apk-audit'; A.mkdir(parents=True,exist_ok=False)
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
assert manifest.get(android+'versionName')=='0.4.0' and manifest.get(android+'versionCode')=='4'
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
records=['domain-02','connection-03','interop-03','host-03','lint-03','apk-01','instrumentation-01']
expected={'domain-02':22,'connection-03':251,'interop-03':34,'host-03':172}
summaries={name:json.loads((E/'runs'/name/'summary.json').read_text()) for name in records}
for name,result in summaries.items():
 assert result['exit_code']==0,(name,result['exit_code'])
 assert '--tests' not in result['command'],'A filtered run is not a complete acceptance campaign'
 assert all(result['tests'][key]==0 for key in ('failed','errors','skipped'))
 assert not result['native_device_execution'] and not result['emulator_execution'] and not result['ci_executed']
 if name in expected:
  assert result['tests']['passed']==expected[name]
  suites={}
  for file in (E/'runs'/name/'junit').glob('TEST-*.xml'):
   suite=ET.parse(file).getroot();assert suite.get('name') not in suites
   counts={k:int(suite.get(k,0)) for k in ('tests','failures','errors','skipped')}
   assert counts['tests']>0 and not any(counts[k] for k in ('failures','errors','skipped'))
   assert len(suite.findall('testcase'))==counts['tests']
   suites[suite.get('name')]=counts
  assert suites=={s['name']:{k:s[k] for k in ('tests','failures','errors','skipped')} for s in result['test_suites']}
  assert sum(s['tests'] for s in suites.values())==expected[name]
assert sum(s['tests']['passed'] for s in summaries.values())==479
# Match the entire runtime/test/build input set, not merely a file count or one module.
frozen=json.loads((E/'runs/host-03/source-hashes.json').read_text());validated=[]
for name,digest in frozen.items():
 path=ROOT/name
 if path.suffix=='.md' or path.name in {'.gitignore','dev-env.sh'}:continue
 assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==digest,('post-validation source change',name)
 validated.append(name)
current_names=set()
for path in P.rglob('*'):
 if not path.is_file() or any(p in {'build','.cache','.gradle','.kotlin'} for p in path.relative_to(P).parts) or path.name=='local.properties':continue
 if path.suffix in {'.md','.jks','.keystore'} or path.name in {'.gitignore','dev-env.sh'}:continue
 current_names.add(path.relative_to(ROOT).as_posix())
assert current_names==set(validated),'Final source set changed'
for record in records:
 other=json.loads((E/'runs'/record/'source-hashes.json').read_text())
 for name in validated:assert other.get(name)==frozen[name],('stage source mismatch',record,name)
lint=ET.parse(E/'runs/lint-03/lint-results-debug.xml').getroot();issues=Counter(n.get('severity') for n in lint.findall('issue'))
assert not any(issues.get(x) for x in ('Fatal','Error','Warning'))
scanner=next(n for n in app.findall('activity') if n.get(android+'name')=='com.narada.android.connection.ScanPairingActivity')
assert scanner.get(android+'exported')=='false'
inspect([sdk_home/'cmdline-tools/latest/bin/apkanalyzer','dex','packages','--defined-only',apk],'dex-packages.txt')
dex=(A/'dex-packages.txt').read_text()
for absent in ('org.bouncycastle','AndroidTestIdentities','TestIdentities','TransfersDesktopInteropTest','TransferTestData'):
 assert absent not in dex,('Test-only code in app APK',absent)
for present in ('com.narada.connection.DownloadEngine','com.narada.connection.TransfersClient','com.narada.android.transfers.BoundedProviderIo','com.narada.android.transfers.ExportPickerBinding'):
 assert present in dex,('Missing Phase 4 implementation',present)
raw=(A/'dex-packages.txt').read_bytes();compressed=lzma.compress(raw,preset=6)
assert lzma.decompress(compressed)==raw
(A/'dex-packages.txt.xz').write_bytes(compressed)
inventory={'raw_sha256':hashlib.sha256(raw).hexdigest(),'raw_bytes':len(raw),'xz_sha256':hashlib.sha256(compressed).hexdigest(),'xz_bytes':len(compressed),'file':'dex-packages.txt.xz','software_fixture_generators_absent':True,'bouncycastle_classes_absent':True,'phase4_implementation_present':True}
(A/'dex-inventory-receipt.json').write_text(json.dumps(inventory,indent=2)+'\n');(A/'dex-packages.txt').unlink()
reference_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'references').iterdir()) if p.suffix.lower() in {'.png','.html'}}
assert len(reference_hashes)==5
history=json.loads((E/'historical-input-hashes.json').read_text());history_result=verify_historical(ROOT,history)
verified=ET.parse(P/'gradle/verification-metadata.xml').getroot();components=verified.find('{https://schema.gradle.org/dependency-verification}components')
old_signer='4867bd2ab95831d130e9d23c21942cca04ad60863f698c9a953f041fc6952c72'
# Signed APK bytes are never changed. XZ is an outer development-delivery container,
# selected to preserve all historical deliveries within the workspace budget.
artifacts=[]
for path,filename in [(apk,'NARADA_Android_Phase4_Debug.apk'),(test_apk,'NARADA_Android_Phase4_Instrumentation.apk')]:
 artifacts.append({'filename':filename,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
archive=ROOT.parent/'NARADA_Android_Phase4_APKs.tar.xz';assert not archive.exists()
notes=('NARADA Android Phase 4 DEVELOPMENT APKs.\nExtract this tar.xz archive before installation; tar/7-Zip or Python tarfile can read it.\nDebug: com.narada.mobile.dev 0.4.0 (4), min API 29, target 36.\n8 instrumentation cases compiled; ZERO device/emulator executions.\nThe earlier Phase 1-3 signing key is unavailable. This signer differs: NO in-place update claim.\nUse a fresh disposable test device/profile. Uninstall/clear-data loses identity/trust; it is NOT lossless repair or migration.\nAPK and instrumentation signatures match each other.\nNo production signing/release/device/Windows-LAN qualification.\nNo private signing, identity or content keys are included.\nVerify SHA256SUMS.txt; see the separate Phase 4 source handoff.\n')
assert certificate!=old_signer,'Reassess signer notes if the original key has been restored'
with tarfile.open(archive,'x:xz',preset=6) as bundle:
 for source,item in zip([apk,test_apk],artifacts):bundle.add(source,arcname=item['filename'])
 for name,data in [('SHA256SUMS.txt',''.join(x['sha256']+'  '+x['filename']+'\n' for x in artifacts)),('READ-ME.txt',notes)]:
  data=data.encode();info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;bundle.addfile(info,io.BytesIO(data))
with tarfile.open(archive,'r:xz') as bundle:
 for item in artifacts:
  data=bundle.extractfile(item['filename']).read();assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
archive_hash=hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_name(archive.name+'.sha256').write_text(archive_hash+'  '+archive.name+'\n')
report={'application_id':manifest.get('package'),'version':'0.4.0','version_code':4,'min_sdk':29,'target_sdk':36,'metadata_schema':3,'sealed_trust_payload_version':3,'supported_trust_payload_versions':[1,2,3],'test_count':479,'new_phase4_checks':182,'compiled_instrumentation_test_cases':8,'development_debuggable':True,'artifacts':artifacts,'apk_bundle':{'filename':archive.name,'format':'tar.xz','bytes':archive.stat().st_size,'sha256':archive_hash},'tests':{'domain_passed':22,'connection_passed':251,'real_rust_interop_passed':34,'android_host_passed':172,'failed':0,'errors':0,'skipped':0},'lint_severities':dict(issues),'lint_policy':'Three pinned-version update-advisory categories remain informational; no application lint baseline.','permissions':permissions,'exported_components':exported,'file_provider_scope':'cacheDir/support only; one temporary read URI grant. SAF exports use the selected external provider, not this provider.','signature_v2_verified':True,'debug_certificate_sha256':certificate,'matching_instrumentation_signature_verified':True,'original_phase1_signer_preserved':False,'previous_phase1_to_3_signer_sha256':old_signer,'in_place_upgrade_with_supplied_apk_qualified':False,'test_only_software_crypto_not_bundled':True,'no_new_android_permissions':True,'outbound_only_no_phone_listener':True,'transfer_slice':'foreground-only desktop-to-phone ordinary files; verified encrypted private receipt; separate explicit create-new SAF export','download_records_limit':4,'download_aggregate_limit_bytes':134217728,'export_records_limit':2,'production_transfer_admission_spacing_ms':300,'provider_io_absolute_budget_ms':90000,'zip_16kb_alignment_checked':True,'native_elf_alignment':libs,'native_execution':False,'emulator_execution':False,'instrumentation_built_not_executed':True,'ci_executed':False,'runtime_test_build_inputs_matched_across_final_stages':len(validated),'reference_sha256':reference_hashes,'verified_dependency_components':len(components) if components is not None else 0,'checksum_trust':'TOFU over official Maven/Google repositories, not independent signature/security review','final_stage_records':records,'dex_inventory':inventory,'historical_files_verified_unchanged':history_result['files'],'historical_verification':history_result}
(E/'validation-summary.json').write_text(json.dumps(report,indent=2)+'\n')
(E/'validated-input-hashes.json').write_text(json.dumps({name:frozen[name] for name in validated},indent=2)+'\n')
print(json.dumps(report,indent=2))
