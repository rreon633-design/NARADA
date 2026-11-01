#!/usr/bin/env python3
"""Audit an already-built development artifact. This does not execute Android/desktop tests."""
import argparse,gzip,hashlib,io,json,lzma,os,re,struct,subprocess,tarfile,zipfile
from android_phase5_history import verify_historical,historical_bytes
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--record',default='apk-audit');parser.add_argument('--reuse-apk-bundle',action='store_true');args=parser.parse_args();assert re.fullmatch(r'[a-z0-9-]+',args.record)
E=ROOT/'artifacts/android-phase-5'; A=E/args.record; A.mkdir(parents=True,exist_ok=False)
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
assert manifest.get(android+'versionName')=='0.5.0' and manifest.get(android+'versionCode')=='5'
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
native=json.loads((P/'app/build/generated/olm/build-receipt.json').read_text())
for name,digest in native['source_sha256'].items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest
host_tests=json.loads((E/'preparation/olm-host-05.json').read_text())
assert host_tests['exit_code']==0 and host_tests['passed']==12 and host_tests['source_sha256_before_and_after']==native['source_sha256']
assert hashlib.sha256((E/'preparation/olm-host-05.log').read_bytes()).hexdigest()==host_tests['log_sha256']
abis={'arm64-v8a':183,'armeabi-v7a':40,'x86_64':62,'x86':3};native_checks=[]
with zipfile.ZipFile(apk) as z:
 for abi,machine in abis.items():
  name='lib/'+abi+'/libnarada_olm.so';data=z.read(name);row=next(x for x in native['libraries'] if x['abi']==abi)
  original=ROOT/row['path'];assert hashlib.sha256(original.read_bytes()).hexdigest()==row['sha256'] and original.stat().st_size==row['bytes']
  merged=P/'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib'/abi/'libnarada_olm.so'
  agp_output=P/'app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib'/abi/'libnarada_olm.so'
  assert merged.read_bytes()==original.read_bytes() and agp_output.read_bytes()==data
  # AGP legitimately applies NDK llvm-strip after Cargo's stripping. Reproduce that exact
  # recorded transformation rather than pretending the APK contains the pre-AGP bytes.
  stripped=P/'app/build/native-audit'/abi/'libnarada_olm.so';stripped.parent.mkdir(parents=True,exist_ok=True)
  strip_tool=tools/'android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
  inspect([strip_tool,'--strip-unneeded','-o',stripped,original],abi+'-strip.txt')
  assert stripped.read_bytes()==data,('Packaged native binary differs from reproduced NDK strip',abi)
  packaged_hash=hashlib.sha256(data).hexdigest()
  assert struct.unpack_from('<H',data,18)[0]==machine
  item=next(x for x in libs if x['path']==name);assert all(v>=16384 for v in item['load_alignment_bytes'])
  inspect([tools/'android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf','--program-headers','--dynamic','--dyn-syms',stripped],abi+'-elf.txt')
  report=(A/(abi+'-elf.txt')).read_text();assert 'Java_com_narada_connection_NativeOlmBridge_call' in report
  assert 'GNU_RELRO' in report and ('BIND_NOW' in report or 'NOW' in report)
  stack=next(line for line in report.splitlines() if 'GNU_STACK' in line);assert 'E' not in stack.split('GNU_STACK')[1],stack
  native_checks.append({'abi':abi,'machine':machine,'built_input_sha256':row['sha256'],'bundled_sha256':packaged_hash,'bundled_bytes':len(data),'merged_equals_receipted_input':True,'agp_stripped_equals_apk':True,'ndk_strip_tool_sha256':hashlib.sha256(strip_tool.read_bytes()).hexdigest(),'ndk_strip_command':'llvm-strip --strip-unneeded','ndk_strip_reproduced_exactly':True,'jni_export_present':True,'relro_and_bind_now':True,'non_executable_stack':True,'elf_16k_load_alignment':True,'executed_on_android':False})
 notices=json.loads((E/'native-dependencies.json').read_text());notice=z.read('assets/native-olm-notices.txt')
 # AGP transparently expands .gz source assets while merging; verify the expanded APK bytes.
 source_notice=P/'app/src/main/assets/native-olm-notices.txt.gz'
 assert hashlib.sha256(source_notice.read_bytes()).hexdigest()==notices['notice_asset_sha256']
 assert gzip.decompress(source_notice.read_bytes())==notice
 assert hashlib.sha256(notice).hexdigest()==notices['notice_sha256']
 assert hashlib.sha256((P/'native-olm/Cargo.lock').read_bytes()).hexdigest()==notices['cargo_lock_sha256']
assert len(native_checks)==4
(E/'native-binary-audit.json').write_text(json.dumps({'build_receipt':native,'host_rust_tests':host_tests,'android_libraries':native_checks,'android_execution':False,'independent_review':False},indent=2)+'\n')
records=['domain-02','connection-03','interop-02','host-02','lint-01','apk-01','instrumentation-01']
expected={'domain-02':22,'connection-03':299,'interop-02':43,'host-02':205}
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
assert sum(s['tests']['passed'] for s in summaries.values())==569
# Match the entire runtime/test/build input set, not merely a file count or one module.
frozen=json.loads((E/'runs/host-02/source-hashes.json').read_text());validated=[]
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
lint=ET.parse(E/'runs/lint-01/lint-results-debug.xml').getroot();issues=Counter(n.get('severity') for n in lint.findall('issue'))
assert not any(issues.get(x) for x in ('Fatal','Error','Warning'))
scanner=next(n for n in app.findall('activity') if n.get(android+'name')=='com.narada.android.connection.ScanPairingActivity')
assert scanner.get(android+'exported')=='false'
inspect([sdk_home/'cmdline-tools/latest/bin/apkanalyzer','dex','packages','--defined-only',apk],'dex-packages.txt')
dex=(A/'dex-packages.txt').read_text()
raw=(A/'dex-packages.txt').read_bytes();compressed=lzma.compress(raw,preset=6)
assert lzma.decompress(compressed)==raw
(A/'dex-packages.txt.xz').write_bytes(compressed)
inventory={'raw_sha256':hashlib.sha256(raw).hexdigest(),'raw_bytes':len(raw),'xz_sha256':hashlib.sha256(compressed).hexdigest(),'xz_bytes':len(compressed),'file':'dex-packages.txt.xz','software_fixture_generators_absent':True,'bouncycastle_classes_absent':True,'phase5_and_prior_implementations_present':True}
(A/'dex-inventory-receipt.json').write_text(json.dumps(inventory,indent=2)+'\n');(A/'dex-packages.txt').unlink()
for absent in ('org.bouncycastle','AndroidTestIdentities','TestIdentities','TransfersDesktopInteropTest','TransferTestData','ChatFixture','ChatCoordinatorTest','ChatDesktopInteropTest'):
 assert absent not in dex,('Test-only code in app APK',absent)
for present in ('com.narada.connection.DownloadEngine','com.narada.connection.TransfersClient','com.narada.android.transfers.BoundedProviderIo','com.narada.android.transfers.ExportPickerBinding','com.narada.connection.ChatEngine','com.narada.connection.ChatClient','com.narada.connection.NativeOlmBridge','com.narada.android.chat.ChatCoordinator'):
 assert present in dex,('Missing Phase 5 implementation',present)
reference_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'references').iterdir()) if p.suffix.lower() in {'.png','.html'}}
assert len(reference_hashes)==5
history=json.loads((E/'historical-input-hashes.json').read_text());history_result=verify_historical(ROOT,history)
verified=ET.parse(P/'gradle/verification-metadata.xml').getroot();components=verified.find('{https://schema.gradle.org/dependency-verification}components')
previous=json.loads(historical_bytes(ROOT,'artifacts/android-phase-4/validation-summary.json'))
previous_signature=historical_bytes(ROOT,'artifacts/android-phase-4/apk-audit/signature.txt').decode()
previous_signer=previous['debug_certificate_sha256']
assert previous_signer==re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',previous_signature).group(1)
old_signer=previous['previous_phase1_to_3_signer_sha256']
# Signed APK bytes are never changed. XZ is an outer development-delivery container,
# selected to preserve all historical deliveries within the workspace budget.
artifacts=[]
for path,filename in [(apk,'NARADA_Android_Phase5_Debug.apk'),(test_apk,'NARADA_Android_Phase5_Instrumentation.apk')]:
 artifacts.append({'filename':filename,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
archive=ROOT.parent/'NARADA_Android_Phase5_APKs.tar.xz';assert archive.is_file() if args.reuse_apk_bundle else not archive.exists()
notes=('NARADA Android Phase 5 DEVELOPMENT APKs.\nExtract this tar.xz archive before installation; tar/7-Zip or Python tarfile can read it.\nDebug: com.narada.mobile.dev 0.5.0 (5), min API 29, target 36.\n10 instrumentation cases compiled; ZERO device/emulator executions.\nThe earlier Phase 1-4 signing keys are unavailable. This signer differs: NO in-place update claim.\nUse a fresh disposable test device/profile. Uninstall/clear-data loses identity/trust; it is NOT lossless repair or migration.\nAPK and instrumentation signatures match each other.\nNo production signing/release/device/Windows-LAN qualification.\nNo private signing, identity or content keys are included.\nVerify SHA256SUMS.txt; see the separate Phase 5 source handoff.\n')
assert certificate not in {old_signer,previous_signer},'Reassess signer notes if the original key has been restored'
if not args.reuse_apk_bundle:
 with tarfile.open(archive,'x:xz',preset=6) as bundle:
  for source,item in zip([apk,test_apk],artifacts):bundle.add(source,arcname=item['filename'])
  for name,data in [('SHA256SUMS.txt',''.join(x['sha256']+'  '+x['filename']+'\n' for x in artifacts)),('READ-ME.txt',notes)]:
   data=data.encode();info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;bundle.addfile(info,io.BytesIO(data))
with tarfile.open(archive,'r:xz') as bundle:
 assert set(bundle.getnames())=={x['filename'] for x in artifacts}|{'READ-ME.txt','SHA256SUMS.txt'}
 assert bundle.extractfile('READ-ME.txt').read().decode()==notes
 assert bundle.extractfile('SHA256SUMS.txt').read().decode()==''.join(x['sha256']+'  '+x['filename']+'\n' for x in artifacts)
 for item in artifacts:
  data=bundle.extractfile(item['filename']).read();assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
archive_hash=hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_name(archive.name+'.sha256').write_text(archive_hash+'  '+archive.name+'\n')
report={'application_id':manifest.get('package'),'version':'0.5.0','version_code':5,'min_sdk':29,'target_sdk':36,'metadata_schema':3,'sealed_trust_payload_version':4,'supported_trust_payload_versions':[1,2,3,4],'test_count':581,'new_phase5_checks':102,'compiled_instrumentation_test_cases':10,'development_debuggable':True,'artifacts':artifacts,'apk_bundle':{'filename':archive.name,'format':'tar.xz','bytes':archive.stat().st_size,'sha256':archive_hash},'tests':{'domain_passed':22,'rust_adapter_host_passed':12,'connection_passed':299,'real_rust_interop_passed':43,'android_host_passed':205,'failed':0,'errors':0,'skipped':0},'lint_severities':dict(issues),'lint_policy':'Three pinned-version update-advisory categories remain informational; no application lint baseline.','permissions':permissions,'exported_components':exported,'file_provider_scope':'cacheDir/support only; one temporary read URI grant. SAF exports use the selected external provider, not this provider.','signature_v2_verified':True,'debug_certificate_sha256':certificate,'matching_instrumentation_signature_verified':True,'original_phase1_signer_preserved':False,'previous_phase1_to_3_signer_sha256':old_signer,'previous_phase4_signer_sha256':previous_signer,'in_place_upgrade_with_supplied_apk_qualified':False,'test_only_software_transport_fixtures_not_bundled':True,'production_olm_software_crypto_bundled':True,'no_new_android_permissions':True,'outbound_only_no_phone_listener':True,'transfer_slice':'foreground-only desktop-to-phone ordinary files; verified encrypted private receipt; separate explicit create-new SAF export','download_records_limit':4,'download_aggregate_limit_bytes':134217728,'export_records_limit':2,'production_transfer_admission_spacing_ms':300,'provider_io_absolute_budget_ms':90000,'zip_16kb_alignment_checked':True,'native_elf_alignment':libs,'native_execution':False,'emulator_execution':False,'instrumentation_built_not_executed':True,'ci_executed':False,'runtime_test_build_inputs_matched_across_final_stages':len(validated),'reference_sha256':reference_hashes,'verified_dependency_components':len(components) if components is not None else 0,'checksum_trust':'TOFU over official Maven/Google repositories, not independent signature/security review','final_stage_records':records,'dex_inventory':inventory,'historical_files_verified_unchanged':history_result['files'],'historical_verification':history_result}
report.update({'artifact_audit_directory':A.relative_to(ROOT).as_posix(),'sealed_store_max_bytes':2097152,'chat_message_limit':32,'chat_peer_limit':4,'chat_sessions_per_peer_limit':2,'text_utf8_bytes_limit':4096,'crypto':'vodozemac 0.11.1 / Olm v1 / 64-bit MAC','binding':'stateless Rust JNI 0.21.1','independent_crypto_storage_review':False,'foreground_text_only':True,'native_rust_host_test_count':12,'native_binary_audit':'native-binary-audit.json','shared_content_admission_spacing_ms':300,'known_limits':['No Android/device/emulator execution','No whole-state rollback resistance','No independent review or release approval','No same-signer in-place upgrade qualification']})
(E/'validation-summary.json').write_text(json.dumps(report,indent=2)+'\n')
(E/'validated-input-hashes.json').write_text(json.dumps({name:frozen[name] for name in validated},indent=2)+'\n')
print(json.dumps(report,indent=2))
