#!/usr/bin/env python3
"""Package measured Phase 3 source + selected evidence. No build, tests, profiles or signing keys.
Original desktop/Android evidence and earlier delivered ZIP bytes are independently checked.
"""
import ast, hashlib, json, posixpath, re, tarfile, zipfile
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT=Path(__file__).resolve().parents[1]; OUT=ROOT.parent
PREFIX='NARADA_Android_Phase3'; E=ROOT/'artifacts/android-phase-3'
SKIP={'build','.cache','.gradle','.kotlin','.git','.idea','node_modules','__pycache__','.venv','target','out','dist','coverage','.historical-runtime'}
entries={}; origins={}
def include(path):
    if not path.is_file() or path.is_symlink():return
    rel=path.relative_to(ROOT)
    if any(part in SKIP for part in rel.parts):return
    if path.name in {'local.properties','.DS_Store'} or path.suffix in {'.jks','.keystore','.apk','.idsig','.p12','.pfx','.key'}:return
    name=rel.as_posix();entries[name]=path.read_bytes();origins[name]=name

for folder in ('apps/android','docs/android','crates','apps/windows-agent','apps/desktop-shell/src-tauri','tests/corpus'):
    for p in sorted((ROOT/folder).rglob('*')):include(p)
# All Phase 3 stage logs/XML/receipts, current renders, and preparation corrections.
# Verbose DEX, superseded renders, binary JUnit cache and their larger exact-byte archives remain in workspace.
for p in sorted(E.rglob('*')):
    name=p.relative_to(ROOT).as_posix()
    if p.name in {'dex-packages.txt.gz','earlier-host-renders.tar.xz'} or '/junit/binary/' in name:continue
    if '/host-renders/' in name and '/runs/host-03/' not in name:continue
    include(p)
for stage, names in {
    'android-phase-1':('validation-summary.json','validated-input-hashes.json','apk-audit/AndroidManifest.xml','apk-audit/signature.txt','apk-audit/instrumentation-signature.txt','apk-audit/zipalign.txt','runs/host-05/summary.json','runs/lint-02/lint-results-debug.xml'),
    'android-phase-2':('validation-summary.json','validated-input-hashes.json','historical-input-hashes.json','apk-audit-02/AndroidManifest.xml','apk-audit-02/signature.txt','apk-audit-02/instrumentation-signature.txt','apk-audit-02/zipalign.txt','apk-audit-02/dex-inventory-receipt.json','runs/host-03/summary.json','runs/interop-03/summary.json','runs/lint-03/lint-results-debug.xml'),
}.items():
    for name in names:include(ROOT/'artifacts'/stage/name)
for name in ('Cargo.toml','Cargo.lock','rust-toolchain.toml','android-plan.md','ANDROID-PHASE1-HANDOFF.md','ANDROID-PHASE2-HANDOFF.md','ANDROID-PHASE3-HANDOFF.md','.github/workflows/android-foundation.yml','scripts/validate_android_phase3.py','scripts/android_memory_guard.py','scripts/audit_android_phase3.py','scripts/package_android_phase3.py'):
    include(ROOT/name)
for p in sorted((ROOT/'references').iterdir()):
    if p.suffix.lower() in {'.png','.html'}:include(p)
entries['README.md']=('''# NARADA — Android Phase 3 source

Open `apps/android` in Android Studio; start with its README and the split docs. This is native Kotlin/Compose source, not a WebView or a desktop frontend bundle. Extract the separate APK ZIP before installation.

Included: final measured Android inputs, split phase docs, Phase 3 logs/XML/current host renders and preparation corrections, selected prior Android receipts, all five unchanged supplied visual references, and the unchanged Rust peer/workspace source for real interop. The complete desktop frontend/history and prior full Android campaigns remain in their separate preserved deliveries/workspace. Verbose DEX inventories and earlier host-render archives stay in the workspace with included receipts; no private profiles, SDK caches or signing keys are shipped. Remote CI source is included but was not executed.

Earlier delivered ZIP bytes are preserved separately, including eight older ZIPs in the persisted `NARADA_Pre_Android_Phase2_Deliveries.tar.xz`. See the Phase 3 historical relocation receipt for exact member hashes. Android Phase 2 source/APK ZIPs are unchanged at their original names. These historical containers are not nested in this source bundle.

'''+(ROOT/'ANDROID-PHASE3-HANDOFF.md').read_text()).encode()
origins['README.md']='generated standalone package index + current Phase 3 handoff'
entries['references/README.md']='''# Original supplied visual references

[Android plan](../android-plan.md) · [Native architecture](../docs/android/architecture.md)

The five images/HTML files are byte-identical to the supplied assets. Prototype branding, devices, messages/progress and scripts are references, not production behavior.

- [Android screenshot](android-screens-reference.png) — mobile direction.
- [Android HTML](original-android-prototype.html) — supplied `IFB_Android_Mobile_All_Screens.html`, not executable app code.
- [Desktop Files](desktop-files-reference.png) and [desktop overview](desktop-screens-reference.png) — preserved desktop references.
- [Desktop HTML](original-desktop-prototype.html) — supplied `IFB_Desktop_All_16_Screens.html`.

Phase 3 uses native authorized file states and explicit managed actions. Transfers/E2EE/offline engines remain later Android phases. Full desktop documentation is separate.
'''.encode()
origins['references/README.md']='generated standalone index; original assets unchanged'

# Include the limited local-link closure for historical Android documentation, rather than broken links.
def local_targets(name,data):
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',data.decode()):
        target=target.split(' "')[0].strip('<>')
        if urlsplit(target).scheme or target.startswith('#'):continue
        rel=unquote(urlsplit(target).path)
        if not rel:continue
        resolved=posixpath.normpath(posixpath.join(posixpath.dirname(name),rel))
        yield target,resolved
for _ in range(8):
    before=len(entries)
    for name,data in list(entries.items()):
        if not name.endswith('.md'):continue
        for target,resolved in local_targets(name,data):
            if resolved in entries or any(n.startswith(resolved.rstrip('/')+'/') for n in entries):continue
            assert not resolved.startswith(('/', '../')),('outside bundle link',name,target)
            source=ROOT/resolved
            if source.is_file():include(source)
            elif source.is_dir():
                for path in sorted(source.rglob('*')):
                    if path.name not in {'dex-packages.txt.gz','earlier-host-renders.tar.xz'} and '/junit/binary/' not in path.as_posix():include(path)
    if len(entries)==before:break

validated=json.loads((E/'validated-input-hashes.json').read_text())
for name,digest in validated.items():assert hashlib.sha256(entries[name]).hexdigest()==digest,('validated source changed',name)
historical=json.loads((E/'historical-input-hashes.json').read_text())
for name,digest in historical.items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,('frozen historical input changed',name)
prior_history=json.loads((ROOT/'artifacts/android-phase-2/historical-input-hashes.json').read_text())
for name,digest in prior_history.items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,('earlier desktop/foundation history changed',name)
for filename,digest in {
    'NARADA_Android_Phase2_Source.zip':'780d9813c32ddffe020fea0ae4f20c76e721138717bc58ade7f3872b2e5bc917',
    'NARADA_Android_Phase2_APKs.zip':'0eaf6d3007d8edd5df331212ee234939afb8ba786c10b02441eec76850097a7a',
}.items():assert hashlib.sha256((OUT/filename).read_bytes()).hexdigest()==digest
relocation=json.loads((E/'preparation/historical-archive-relocation.json').read_text())
legacy=OUT/relocation['archive'];assert hashlib.sha256(legacy.read_bytes()).hexdigest()==relocation['archive_sha256']
with tarfile.open(legacy,'r:xz') as archive:
    assert set(archive.getnames())==set(relocation['members'])
    for member in archive:
        data=archive.extractfile(member).read();receipt=relocation['members'][member.name]
        assert len(data)==receipt['bytes'] and hashlib.sha256(data).hexdigest()==receipt['sha256']
    assert relocation['members']['NARADA_Desktop_5-9_Source.zip']['sha256']=='d6b15bc1fbcc028bc50407453c5ad37bd8e381c9ae11fe4369610a728acd892d'
    assert relocation['members']['NARADA_Android_Phase1_Source.zip']['sha256']=='eef65d405a1a008e896da44c4b562517f25c8a266d5f4f53ab593941df3bfbff'

links=0; broken=[]
for name,data in entries.items():
    assert not name.startswith('/') and '..' not in Path(name).parts and not any(part in SKIP for part in Path(name).parts)
    assert not re.search(r'(?i)(\.keystore|\.jks|\.p12|\.pfx|\.key)$',name),name
    if name.endswith('.py'):ast.parse(data.decode(),filename=name)
    if name.endswith(('.kt','.kts','.py','.md','.yml','.toml','.properties','.xml','.rs')):
        assert not re.search(rb'(?m)^-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\r?$',data),name
    if name.endswith('.md'):
        for target,resolved in local_targets(name,data):
            links+=1
            if resolved not in entries and not any(n.startswith(resolved.rstrip('/')+'/') for n in entries):broken.append((name,target,resolved))
assert not broken,broken
summary=json.loads((E/'validation-summary.json').read_text())
assert summary['test_count']==297 and summary['runtime_test_build_inputs_matched_across_final_stages']==len(validated)
for name,digest in summary['reference_sha256'].items():assert hashlib.sha256(entries['references/'+name]).hexdigest()==digest
apk_bundle=OUT/summary['apk_bundle']['filename'];assert hashlib.sha256(apk_bundle.read_bytes()).hexdigest()==summary['apk_bundle']['sha256']
with zipfile.ZipFile(apk_bundle) as bundle:
    assert bundle.testzip() is None
    for item in summary['artifacts']:assert hashlib.sha256(bundle.read(item['filename'])).hexdigest()==item['sha256']
rust={n:hashlib.sha256(data).hexdigest() for n,data in entries.items() if n.startswith(('crates/','apps/windows-agent/')) or n in {'Cargo.lock','Cargo.toml','rust-toolchain.toml'}}
manifest={'format':1,'scope':'Android Phase 3 development source and selected measured evidence; unchanged real Rust peer source','validated_android_input_count':len(validated),'current_host_interop_tests_passed':297,'native_execution':False,'ci_executed':False,'rust_peer_source_sha256':rust,'files':{n:{'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'origin':origins[n]} for n,data in sorted(entries.items())}}
entries['SOURCE-MANIFEST.json']=(json.dumps(manifest,indent=2)+'\n').encode()
archive=OUT/(PREFIX+'_Source.zip')
assert not archive.exists(),'Never overwrite a published source delivery'
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for name,data in sorted(entries.items()):
        info=zipfile.ZipInfo(PREFIX+'/'+name,date_time=(2026,10,31,0,0,0));info.create_system=3
        mode=0o100755 if name.endswith(('.py','.sh')) or name.endswith('/gradlew') else 0o100644
        info.external_attr=mode<<16;info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for name,data in entries.items():assert z.read(PREFIX+'/'+name)==data
ziphash=hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_suffix('.zip.sha256').write_text(ziphash+'  '+archive.name+'\n')
checksums=[hashlib.sha256((OUT/name).read_bytes()).hexdigest()+'  '+name for name in (archive.name,apk_bundle.name)]
checksums.extend(x['sha256']+'  '+x['filename'] for x in summary['artifacts'])
(OUT/(PREFIX+'_SHA256SUMS.txt')).write_text('\n'.join(checksums)+'\n')
report={'archive':archive.name,'bytes':archive.stat().st_size,'sha256':ziphash,'files':len(entries),'markdown_documents':sum(n.endswith('.md') for n in entries),'local_markdown_links_checked':links,'validated_android_inputs_matched':len(validated),'historical_frozen_files_unchanged':len(historical),'earlier_phase2_historical_inputs_unchanged':len(prior_history),'legacy_zip_members_roundtrip_verified':len(relocation['members']),'phase2_delivered_zip_bytes_unchanged':True,'rust_peer_source_files':len(rust),'original_reference_assets_matched':len(summary['reference_sha256']),'production_private_keys_profiles_or_sdk_caches_included':False,'zip_crc_and_member_bytes_verified':True,'desktop_historical_tests_rerun':False,'native_tests_executed':False,'remote_ci_executed':False,'note':'Final host-03 renders included. Verbose gzip DEX and earlier render tar.xz remain in workspace with receipts; earlier full Android/desktop histories are separate preserved deliveries.'}
(OUT/(PREFIX+'_Package_Check.json')).write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
