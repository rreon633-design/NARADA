#!/usr/bin/env python3
"""Audit measured Phases 6/7 development artifacts; never executes historical desktop/device tests."""
import argparse, gzip, hashlib, io, json, lzma, os, re, struct, subprocess, tarfile, zipfile
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET
from android_phase67_history import historical_bytes, verify_historical
from android_phase67_inputs import android_inputs, desktop_inputs, sha
ROOT=Path(__file__).resolve().parents[1];P=ROOT/'apps/android';E=ROOT/'artifacts/android-phase-6-7'
p=argparse.ArgumentParser();p.add_argument('--record',required=True);p.add_argument('--reuse-apk-bundle',action='store_true');a=p.parse_args();assert re.fullmatch('[a-z0-9-]+',a.record)
A=E/a.record;A.mkdir(parents=True,exist_ok=False)
records={'domain':'domain-02','connection':'connection-06','interop':'interop-05','host':'host-09','lint':'lint-03','instrumentation':'instrumentation-01','apk':'apk-01'}
expected={'domain':22,'connection':418,'interop':65,'host':259}
frozen=android_inputs(ROOT);assert len(frozen)==223
summaries={}
for stage,record in records.items():
    folder=E/'runs'/record;s=json.loads((folder/'summary.json').read_text());summaries[stage]=s
    assert s['stage']==stage and s['exit_code']==0 and not s.get('test_output_missing') and s.get('test_filter') is None
    assert s['source_before_after_identical'] and s['source_count']==len(frozen)
    assert json.loads((folder/'source-hashes.json').read_text())==frozen,('Source drift',record)
    assert sha(folder/'source-hashes.json')==s['source_manifest_sha256'] and sha(folder/'gradle.log')==s['gradle_log_sha256']
    for name,digest in s['tool_sha256'].items():assert sha(folder/'tool-sources'/Path(name).name)==digest
    assert not any(s['tests'][n] for n in ('failed','errors','skipped'))
    if stage in expected:
        assert s['tests']['passed']==expected[stage]
        suites={}
        for report in (folder/'junit').glob('TEST-*.xml'):
            node=ET.parse(report).getroot();counts={k:int(node.get(k,0)) for k in ('tests','failures','errors','skipped')}
            assert counts['tests']>0 and not any(counts[k] for k in ('failures','errors','skipped')) and len(node.findall('testcase'))==counts['tests']
            assert node.get('name') not in suites;suites[node.get('name')]=counts
        assert suites=={v['name']:{k:v[k] for k in ('tests','failures','errors','skipped')} for v in s['test_suites']}
        assert sum(v['tests'] for v in suites.values())==expected[stage]
peer=json.loads((E/'runs'/records['interop']/'peer-build-receipt.json').read_text())
assert peer['exit_code']==0 and peer['desktop_source_sha256_before_and_after']==desktop_inputs(ROOT)
assert (E/'runs'/records['interop']/'agent-binary-sha256.txt').read_text().strip()==peer['binary_sha256']==sha(peer['binary'])
assert json.loads((E/'runs'/records['interop']/'desktop-source-hashes.json').read_text())==desktop_inputs(ROOT)
assert not peer['historical_desktop_validation_run'] and not peer['windows_execution']
lint=ET.parse(E/'runs'/records['lint']/'lint-results-debug.xml').getroot();issues=Counter(i.get('severity') for i in lint.findall('issue'))
assert not any(issues.get(k) for k in ('Fatal','Error','Warning'))
compiled=sum(len(re.findall(r'@Test\b',f.read_text())) for f in (P/'app/src/androidTest').rglob('*.kt'));assert compiled==13
renders=sorted((E/'runs'/records['host']/'host-renders').glob('*.png'));assert len(renders)==28
render_inventory=[{'path':f.relative_to(ROOT).as_posix(),'bytes':f.stat().st_size,'sha256':sha(f),'environment':'synthetic API35 Robolectric/native graphics, NOT a device capture'} for f in renders]
(A/'host-renders.json').write_text(json.dumps(render_inventory,indent=2)+'\n')
# Source-bound native tests and BOTH sets of host/four-ABI libraries.
rust=json.loads((E/'native-runs/host-01/summary.json').read_text());assert rust['exit_code']==0 and rust['passed']==32
native={}
for library,count in [('age',12),('olm',20)]:
    receipt=json.loads((P/('app/build/generated/'+library+'/build-receipt.json')).read_text());native[library]=receipt
    tests=rust['libraries'][library]
    assert tests['exit_code']==0 and tests['passed']==count and tests['failed']==tests['ignored']==0
    assert tests['source_sha256_before_and_after']==receipt['source_sha256'] and sha(E/('native-runs/host-01/'+library+'.log'))==tests['log_sha256']
    for name,digest in receipt['source_sha256'].items():assert sha(ROOT/name)==digest
    for item in receipt['libraries']:assert sha(ROOT/item['path'])==item['sha256'] and (ROOT/item['path']).stat().st_size==item['bytes']
    for record in records.values():assert json.loads((E/'runs'/record/('native-'+library+'-build-receipt.json')).read_text())==receipt
    assert len(receipt['libraries'])==5 and receipt['bridge_version']==(1 if library=='age' else 2)
# Every inspection below runs against the actual APKs, not reused text reports.
apk=P/'app/build/outputs/apk/debug/app-debug.apk';test_apk=P/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
env=os.environ.copy();tools=Path(env.get('NARADA_ANDROID_TOOLS',str(Path.home()/'.cache/narada-android-tools')));sdk=Path(env.get('ANDROID_HOME',str(tools/'sdk')))
ndk=Path(env.get('ANDROID_NDK_HOME',str(sdk/'ndk/30.0.16248370')));llvm=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
if not env.get('JAVA_HOME') and (tools/'java-home.txt').exists():env['JAVA_HOME']=(tools/'java-home.txt').read_text().strip()
def inspect(args,name):
    result=subprocess.run([str(x) for x in args],env=env,capture_output=True,text=True);(A/name).write_text(result.stdout+result.stderr)
    assert result.returncode==0,('Inspection failed',name)
inspect([sdk/'cmdline-tools/latest/bin/apkanalyzer','manifest','print',apk],'AndroidManifest.xml')
inspect([sdk/'build-tools/35.0.0/apksigner','verify','--verbose','--print-certs',apk],'signature.txt')
inspect([sdk/'build-tools/35.0.0/apksigner','verify','--verbose','--print-certs',test_apk],'instrumentation-signature.txt')
inspect([sdk/'build-tools/35.0.0/zipalign','-c','-P','16','-v','4',apk],'zipalign.txt')
android='{http://schemas.android.com/apk/res/android}';manifest=ET.parse(A/'AndroidManifest.xml').getroot();app=manifest.find('application');uses_sdk=manifest.find('uses-sdk')
assert manifest.get('package')=='com.narada.mobile.dev' and manifest.get(android+'versionName')=='0.7.0' and manifest.get(android+'versionCode')=='7'
assert uses_sdk.get(android+'minSdkVersion')=='29' and uses_sdk.get(android+'targetSdkVersion')=='36'
assert app.get(android+'allowBackup')=='false' and app.get(android+'usesCleartextTraffic')=='false' and app.get(android+'debuggable')=='true'
permissions=sorted(n.get(android+'name') for n in manifest.findall('uses-permission'))
assert set(permissions)=={'android.permission.INTERNET','android.permission.CAMERA','android.permission.POST_NOTIFICATIONS','android.permission.WAKE_LOCK','android.permission.ACCESS_NETWORK_STATE','android.permission.RECEIVE_BOOT_COMPLETED','com.narada.mobile.dev.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
exported=[]
for component in app:
    if component.get(android+'exported')=='true':
        name=component.get(android+'name');permission=component.get(android+'permission')
        assert name=='com.narada.android.MainActivity' or permission in {'android.permission.BIND_JOB_SERVICE','android.permission.DUMP'}
        exported.append({'kind':component.tag,'name':name,'permission':permission})
assert not any('PreviewActivity' in (n.get(android+'name') or '') or 'SystemForegroundService' in (n.get(android+'name') or '') for n in app)
scanner=next(n for n in app.findall('activity') if n.get(android+'name')=='com.narada.android.connection.ScanPairingActivity');assert scanner.get(android+'exported')=='false'
provider=next(n for n in app.findall('provider') if n.get(android+'name')=='androidx.core.content.FileProvider');assert provider.get(android+'exported')=='false' and provider.get(android+'grantUriPermissions')=='true'
paths=ET.parse(P/'app/src/main/res/xml/support_paths.xml').getroot();assert len(paths)==1 and paths[0].tag=='cache-path' and paths[0].get('path')=='support/'
for name in ('signature.txt','instrumentation-signature.txt'):
    text=(A/name).read_text();assert 'Verifies' in text and 'Verified using v2 scheme (APK Signature Scheme v2): true' in text
certificate=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',(A/'signature.txt').read_text()).group(1)
assert certificate==re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',(A/'instrumentation-signature.txt').read_text()).group(1)
assert any(v in (A/'zipalign.txt').read_text() for v in ('Verification successful','Verification succesful'))
libs=[];native_checks=[];abis={'arm64-v8a':183,'armeabi-v7a':40,'x86_64':62,'x86':3}
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    for name in z.namelist():
        if not name.endswith('.so'):continue
        data=z.read(name);assert data[:4]==b'\x7fELF';bits=64 if data[4]==2 else 32;order='<' if data[5]==1 else '>'
        phoff=struct.unpack_from(order+('Q' if bits==64 else 'I'),data,32 if bits==64 else 28)[0]
        entry=struct.unpack_from(order+'H',data,54 if bits==64 else 42)[0];count=struct.unpack_from(order+'H',data,56 if bits==64 else 44)[0];alignments=[]
        for i in range(count):
            offset=phoff+i*entry
            if struct.unpack_from(order+'I',data,offset)[0]==1:alignments.append(struct.unpack_from(order+('Q' if bits==64 else 'I'),data,offset+(48 if bits==64 else 28))[0])
        if bits==64:assert all(n>=16384 for n in alignments)
        libs.append({'path':name,'bits':bits,'load_alignment_bytes':alignments,'sha256':hashlib.sha256(data).hexdigest()})
    for library,receipt in native.items():
        for abi,machine in abis.items():
            name='lib/'+abi+'/libnarada_'+library+'.so';data=z.read(name);row=next(v for v in receipt['libraries'] if v['abi']==abi);original=ROOT/row['path']
            merged=P/'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out'/name
            stripped_by_agp=P/'app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out'/name
            assert merged.read_bytes()==original.read_bytes() and stripped_by_agp.read_bytes()==data
            stripped=P/'app/build/native-audit'/abi/('libnarada_'+library+'.so');stripped.parent.mkdir(parents=True,exist_ok=True)
            inspect([llvm/'llvm-strip','--strip-unneeded','-o',stripped,original],library+'-'+abi+'-strip.txt');assert stripped.read_bytes()==data
            assert struct.unpack_from('<H',data,18)[0]==machine and all(v>=16384 for v in next(v for v in libs if v['path']==name)['load_alignment_bytes'])
            log=library+'-'+abi+'-elf.txt';inspect([llvm/'llvm-readelf','--program-headers','--dynamic','--dyn-syms',stripped],log);text=(A/log).read_text()
            jni_symbols=['Java_com_narada_connection_NativeAgeBridge_'+v for v in ('info','validIdentity','encrypt','decrypt')] if library=='age' else ['Java_com_narada_connection_NativeOlmBridge_call']
            assert all(symbol in text for symbol in jni_symbols)
            assert 'GNU_RELRO' in text and ('BIND_NOW' in text or 'NOW' in text)
            stack=next(line for line in text.splitlines() if 'GNU_STACK' in line);assert 'E' not in stack.split('GNU_STACK')[1]
            native_checks.append({'library':library,'abi':abi,'machine':machine,'built_input_sha256':row['sha256'],'bundled_sha256':hashlib.sha256(data).hexdigest(),'bundled_bytes':len(data),'merged_equals_receipted_input':True,'agp_stripped_equals_apk':True,'independently_reproduced_strip_matches':True,'strip_tool_sha256':sha(llvm/'llvm-strip'),'jni_symbols':jni_symbols,'jni_symbols_present':True,'relro_bind_now_nonexec_stack':True,'android_executed':False})
    inventory=json.loads((E/'native-dependencies-03.json').read_text())
    for library,values in inventory['libraries'].items():
        asset=ROOT/values['notice_asset'];assert sha(asset)==values['notice_asset_sha256']
        text=gzip.decompress(asset.read_bytes());assert hashlib.sha256(text).hexdigest()==values['notice_sha256'] and z.read('assets/native-'+library+'-notices.txt')==text
        assert sha(P/('native-'+library+'/Cargo.lock'))==values['cargo_lock_sha256']
        for row in values['packages']:
            for item in row.get('supplemental_notice_files',[]):assert sha(ROOT/item['file'])==item['sha256']
(A/'native-binary-audit.json').write_text(json.dumps({'libraries':native_checks,'native_build_receipts':native,'dependency_inventory_sha256':sha(E/'native-dependencies-03.json'),'independent_review':False,'android_execution':False},indent=2)+'\n')
inspect([sdk/'cmdline-tools/latest/bin/apkanalyzer','dex','packages','--defined-only',apk],'dex-packages.txt');dex=(A/'dex-packages.txt').read_text()
for absent in ('org.bouncycastle','AndroidTestIdentities','TestIdentities','ContentDesktopFixture','ContentCoordinatorFixture','AttachmentFixture','OfflineFixture','ChatFixture','MemoryTrust','AttachmentsCoordinatorTest'):
    assert absent not in dex,('Test-only software fixture in app APK',absent)
for present in ('com.narada.connection.DownloadEngine','com.narada.connection.ChatEngine','com.narada.connection.NativeOlmBridge','com.narada.connection.AttachmentsEngine','com.narada.connection.AttachmentsClient','com.narada.connection.NativeAgeBridge','com.narada.connection.OfflineEngine','com.narada.connection.SyncClient','com.narada.android.attachments.AttachmentsCoordinator','com.narada.android.offline.OfflineCoordinator','com.narada.android.transfers.BoundedProviderIo'):
    assert present in dex,('Missing production implementation',present)
raw=(A/'dex-packages.txt').read_bytes();compressed=lzma.compress(raw,preset=6);assert lzma.decompress(compressed)==raw
(A/'dex-packages.txt.xz').write_bytes(compressed);dex_inventory={'file':'dex-packages.txt.xz','raw_bytes':len(raw),'raw_sha256':hashlib.sha256(raw).hexdigest(),'xz_bytes':len(compressed),'xz_sha256':hashlib.sha256(compressed).hexdigest(),'production_phase6_7_classes_present':True,'test_software_identity_storage_fixtures_absent':True}
(A/'dex-inventory-receipt.json').write_text(json.dumps(dex_inventory,indent=2)+'\n');(A/'dex-packages.txt').unlink()
for base in (P/'app/src/main',P/'core/connection/src/main'):
    for f in base.rglob('*.kt'):assert not re.search(r'\b(?:ServerSocket|ServerSocketChannel|DatagramSocket|HttpServer)\b',f.read_text()),('Unexpected phone listener implementation',f)
history=json.loads((E/'historical-input-hashes.json').read_text());history_result=verify_historical(ROOT,history)
previous=json.loads(historical_bytes(ROOT,'artifacts/android-phase-5/validation-summary.json'));previous_signer=previous['debug_certificate_sha256']
reference_hashes={f.name:sha(f) for f in (ROOT/'references').iterdir() if f.suffix.lower() in {'.png','.html'}};assert len(reference_hashes)==5
verified=ET.parse(P/'gradle/verification-metadata.xml').getroot();components=verified.find('{https://schema.gradle.org/dependency-verification}components')
artifacts=[]
for f,name in [(apk,'NARADA_Android_Phase6-7_Debug.apk'),(test_apk,'NARADA_Android_Phase6-7_Instrumentation.apk')]:artifacts.append({'filename':name,'bytes':f.stat().st_size,'sha256':sha(f)})
archive=ROOT.parent/'NARADA_Android_Phase6-7_APKs.tar.xz'
notes='''NARADA Android Phases 6 & 7 DEVELOPMENT APKs.
Extract this tar.xz container before installing; use tar/7-Zip or Python tarfile.
Application: com.narada.mobile.dev, 0.7.0 (7), minimum API29, target36.
13 instrumentation cases compiled; ZERO Android/device/emulator executions.
No production signing, independent review, Windows LAN or release qualification.
A fresh disposable device/profile is required for evaluation; no in-place upgrade is qualified.
Never uninstall/clear valued data to resolve a signature conflict: it loses identity/trust/history.
The app and instrumentation APK signatures match each other. Verify SHA256SUMS.txt.
No private signing, transport identity or content keys are included.
See the separate source bundle and split native/release gates for limitations.
'''
sums=''.join(v['sha256']+'  '+v['filename']+'\n' for v in artifacts)
assert archive.exists() if a.reuse_apk_bundle else not archive.exists()
if not a.reuse_apk_bundle:
    with tarfile.open(archive,'x:xz',preset=6) as t:
        for f,item in zip((apk,test_apk),artifacts):t.add(f,arcname=item['filename'])
        for name,text in [('READ-ME.txt',notes),('SHA256SUMS.txt',sums)]:
            data=text.encode();info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;t.addfile(info,io.BytesIO(data))
with tarfile.open(archive,'r:xz') as t:
    assert set(t.getnames())=={v['filename'] for v in artifacts}|{'READ-ME.txt','SHA256SUMS.txt'}
    assert t.extractfile('READ-ME.txt').read().decode()==notes and t.extractfile('SHA256SUMS.txt').read().decode()==sums
    for item in artifacts:
        data=t.extractfile(item['filename']).read();assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
assert android_inputs(ROOT)==frozen and desktop_inputs(ROOT)==peer['desktop_source_sha256_before_and_after']
archive.with_name(archive.name+'.sha256').write_text(sha(archive)+'  '+archive.name+'\n')
report={'application_id':'com.narada.mobile.dev','version':'0.7.0','version_code':7,'min_sdk':29,'target_sdk':36,'metadata_schema':3,'sealed_trust_payload_version':6,'supported_trust_payload_versions':[1,2,3,4,5,6],'test_count':sum(expected.values())+32,'tests':{**{k+'_passed':v for k,v in expected.items()},'age_rust_host_passed':12,'olm_rust_host_passed':20,'failed':0,'errors':0,'skipped':0},'new_checks_since_phase5':215,'compiled_instrumentation_test_cases':compiled,'instrumentation_executed':0,'native_device_execution':False,'emulator_execution':False,'windows_execution':False,'remote_ci_executed':False,'independent_crypto_storage_review':False,'release_approval':False,'lint_severities':dict(issues),'development_debuggable':True,'permissions':permissions,'exported_components':exported,'no_new_android_permissions':True,'file_provider_scope':'cacheDir/support only, temporary read URI grant; SAF external providers separate','signature_v2_verified':True,'debug_certificate_sha256':certificate,'matching_instrumentation_signature_verified':True,'previous_phase5_signer_sha256':previous_signer,'signer_differs_from_phase5':certificate!=previous_signer,'in_place_upgrade_qualified':False,'artifacts':artifacts,'apk_bundle':{'filename':archive.name,'bytes':archive.stat().st_size,'sha256':sha(archive),'format':'tar.xz'},'artifact_audit_directory':A.relative_to(ROOT).as_posix(),'zip_16kb_alignment_checked':True,'native_elf_alignment':libs,'native_binary_audit':(A/'native-binary-audit.json').relative_to(ROOT).as_posix(),'native_dependency_inventory':'artifacts/android-phase-6-7/native-dependencies-03.json','dependency_inventory_scope':'all Cargo metadata packages, including target/build-only; not reviewed legal/advisory or binary-reachability SBOM','dex_inventory':dex_inventory,'android_inputs_matched_across_final_stages':len(frozen),'desktop_runtime_build_inputs_fingerprinted':len(desktop_inputs(ROOT)),'actual_peer_binary_sha256':peer['binary_sha256'],'final_stage_records':list(records.values()),'final_native_record':'native-runs/host-01','host_render_count':28,'reference_sha256':reference_hashes,'verified_maven_components':len(components),'checksum_trust':'Repository HTTPS/TOFU checksums, not independent publisher/security qualification','historical_verification':history_result,'historical_desktop_validation_rerun':False,'phone_outbound_only':True,'age_mode':'age 0.12.1 defaults off, fresh X25519 / age v1 STREAM / 8MiB plaintext limit','olm_mode':'vodozemac 0.11.1 defaults off, Olm v1 / 64-bit MAC / bridge2','attachment_jobs':4,'attachment_reserved_ciphertext_limit':33816576,'downloads_plus_attachments_limit':134217728,'download_jobs':4,'download_file_limit':67108864,'chunk_size':262144,'offline_metadata_bytes_limit':393216,'offline_root_limit':2,'offline_entries_per_root':128,'offline_pin_limit':4,'previous_versions_per_pin':1,'export_records_per_copy':2,'shared_content_admission_ms':300,'provider_deadline_ms':90000,'metadata_freshness_implies_byte_availability':False,'silent_eviction_or_destructive_refresh':False,'known_limits':['No Android/emulator/Windows execution','No independent crypto/JNI/application/advisory/legal review','No whole-state rollback resistance','No same-signer/data-upgrade qualification','No hostile-provider stoppability/atomic-publication/physical-durability guarantee','No release approval'],'audit_script_sha256':sha(Path(__file__))}
assert report['test_count']==796
(E/'validated-input-hashes.json').write_text(json.dumps(frozen,indent=2)+'\n')
(E/'validated-desktop-input-hashes.json').write_text(json.dumps(desktop_inputs(ROOT),indent=2)+'\n')
(E/'validation-summary.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
