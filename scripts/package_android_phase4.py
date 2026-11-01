#!/usr/bin/env python3
"""Package measured Phase 4 source + selected evidence, never keys/profiles/build caches.
No Android or historical desktop test is executed. Exact historical bytes are verified.
"""
import ast,hashlib,io,json,posixpath,re,tarfile
from pathlib import Path
from urllib.parse import unquote,urlsplit
from android_phase4_history import historical_bytes,verify_historical
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT.parent
PREFIX='NARADA_Android_Phase4';E=ROOT/'artifacts/android-phase-4'
SKIP={'build','.cache','.gradle','.kotlin','.git','.idea','node_modules','__pycache__','.venv','target','out','dist','coverage','.historical-runtime','.narada-data'}
LARGE={'dex-packages.txt.gz','dex-packages.txt.xz','earlier-host-renders.tar.xz','earlier-validation-runs.tar.xz'}
historical=json.loads((E/'historical-input-hashes.json').read_text())
entries={};origins={}
def include(path):
    if path.is_symlink():return
    rel=path.relative_to(ROOT)
    if any(part in SKIP for part in rel.parts):return
    if path.name in {'local.properties','.DS_Store'}|LARGE or path.suffix in {'.jks','.keystore','.apk','.idsig','.p12','.pfx','.key'}:return
    name=rel.as_posix()
    if '/junit/binary/' in name:return
    if path.is_file():data=path.read_bytes()
    elif name in historical:data=historical_bytes(ROOT,name)
    else:return
    entries[name]=data;origins[name]=name

for folder in ('apps/android','docs/android','crates','apps/windows-agent','apps/desktop-shell/src-tauri','tests/corpus'):
    for path in sorted((ROOT/folder).rglob('*')):include(path)
for path in sorted(E.rglob('*')):
    if '/host-renders/' in path.as_posix() and '/runs/host-03/' not in path.as_posix():continue
    include(path)
for phase,names in {
    'android-phase-1':('validation-summary.json','validated-input-hashes.json','apk-audit/AndroidManifest.xml','apk-audit/signature.txt','apk-audit/instrumentation-signature.txt','apk-audit/zipalign.txt','runs/host-05/summary.json','runs/lint-02/lint-results-debug.xml'),
    'android-phase-2':('validation-summary.json','validated-input-hashes.json','historical-input-hashes.json','apk-audit-02/AndroidManifest.xml','apk-audit-02/signature.txt','apk-audit-02/instrumentation-signature.txt','apk-audit-02/zipalign.txt','apk-audit-02/dex-inventory-receipt.json','runs/host-03/summary.json','runs/interop-03/summary.json','runs/lint-03/lint-results-debug.xml'),
    'android-phase-3':('validation-summary.json','validated-input-hashes.json','historical-input-hashes.json','apk-audit/AndroidManifest.xml','apk-audit/signature.txt','apk-audit/instrumentation-signature.txt','apk-audit/zipalign.txt','apk-audit/dex-inventory-receipt.json','runs/host-03/summary.json','runs/interop-02/summary.json','runs/lint-01/lint-results-debug.xml'),
}.items():
    for name in names:include(ROOT/'artifacts'/phase/name)
for name in ('Cargo.toml','Cargo.lock','rust-toolchain.toml','android-plan.md','ANDROID-PHASE1-HANDOFF.md','ANDROID-PHASE2-HANDOFF.md','ANDROID-PHASE3-HANDOFF.md','ANDROID-PHASE4-HANDOFF.md','.github/workflows/android-foundation.yml','scripts/validate_android_phase4.py','scripts/android_memory_guard.py','scripts/audit_android_phase4.py','scripts/package_android_phase4.py','scripts/android_phase4_history.py'):
    include(ROOT/name)
for path in sorted((ROOT/'references').iterdir()):
    if path.suffix.lower() in {'.png','.html'}:include(path)
entries['README.md']=('''# NARADA — Android Phase 4 source

Extract this tar.xz archive with tar/7-Zip (or Python tarfile), then open `apps/android` in Android Studio. Start with its README and the split docs. Native Kotlin/Compose source, not a WebView or a desktop frontend bundle. The separate APK tar.xz must also be extracted before installation.

Included: final measured Android inputs, split phase docs, current Phase 4 logs/XML/host renders, exact compressed preparation logs, earlier-run summaries/hash receipts, selected prior Android evidence and historical documentation/link targets, all five unchanged supplied visual references and the unchanged Rust peer/workspace source for real interoperability. Generated executable modes are preserved.

The full desktop frontend and every prior campaign/delivery are separate. Twelve original ZIPs remain byte-identical in `NARADA_Pre_Android_Phase4_Deliveries.tar.xz` in the full workspace; the receipt is included here. The verbose DEX inventories and superseded-render/run archives remain in the workspace with included receipts. They are not nested in this selected source handoff. Relocated historical log/HTML link targets, when included, are restored from their exact original archived bytes. No private profiles, signing/identity/content keys, SDK/tool caches or APKs are included in source. CI source is included, not remotely executed.

The supplied APK uses a different debug signer from Phase 1–3. No same-signer in-place upgrade. A source rebuild needs your own controlled signer and environment; do not clear valued app data to resolve a certificate conflict. Integrity manifests are not independent review or production signing.

'''+(ROOT/'ANDROID-PHASE4-HANDOFF.md').read_text()).encode()
origins['README.md']='generated standalone index + current Phase 4 handoff'
entries['references/README.md']='''# Original supplied visual references

[Android plan](../android-plan.md) · [Native architecture](../docs/android/architecture.md)

All five image/HTML assets are byte-identical. Prototype branding/devices/messages/progress/scripts are references, not production behavior.

- [Android screenshot](android-screens-reference.png) and [Android HTML](original-android-prototype.html) — supplied `IFB_Android_Mobile_All_Screens.html`, not executable app code.
- [Desktop Files](desktop-files-reference.png), [desktop overview](desktop-screens-reference.png) and [desktop HTML](original-desktop-prototype.html) — supplied `IFB_Desktop_All_16_Screens.html`.

Phase 4 uses real authorized file/download states and separate explicit export. Android Olm/age/automatic pins-sync remain later gates. The complete desktop documentation is separate.
'''.encode();origins['references/README.md']='generated standalone index; original assets unchanged'

def local_targets(name,data):
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',data.decode()):
        target=target.split(' "')[0].strip('<>')
        if urlsplit(target).scheme or target.startswith('#'):continue
        rel=unquote(urlsplit(target).path)
        if rel:yield target,posixpath.normpath(posixpath.join(posixpath.dirname(name),rel))
for _ in range(12):
    before=len(entries)
    for name,data in list(entries.items()):
        if not name.endswith('.md'):continue
        for target,resolved in local_targets(name,data):
            if resolved in entries or any(n.startswith(resolved.rstrip('/')+'/') for n in entries):continue
            assert not resolved.startswith(('/','../')),('outside bundle link',name,target)
            source=ROOT/resolved
            if source.is_file() or resolved in historical:include(source)
            elif source.is_dir():
                for path in sorted(source.rglob('*')):include(path)
    if len(entries)==before:break

validated=json.loads((E/'validated-input-hashes.json').read_text())
for name,digest in validated.items():
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,('post-audit input change',name)
    assert hashlib.sha256(entries[name]).hexdigest()==digest,('packaged validated input changed',name)
history_result=verify_historical(ROOT,historical)
for name,data in entries.items():
    if name in historical:assert hashlib.sha256(data).hexdigest()==historical[name],('packaged historical bytes changed',name)
relocation=json.loads((E/'preparation/historical-archive-relocation.json').read_text())
legacy=OUT/relocation['archive'];assert hashlib.sha256(legacy.read_bytes()).hexdigest()==relocation['archive_sha256']
with tarfile.open(legacy,'r:xz') as bundle:
    assert set(bundle.getnames())==set(relocation['members'])
    for member in bundle:
        data=bundle.extractfile(member).read();receipt=relocation['members'][member.name]
        assert len(data)==receipt['bytes'] and hashlib.sha256(data).hexdigest()==receipt['sha256']
for filename in ('earlier-validation-runs.json','preparation-logs.json'):
    receipt=json.loads((E/'preparation'/filename).read_text());archive=E/receipt['archive']
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==receipt['sha256']
    with tarfile.open(archive,'r:xz') as bundle:
        assert set(bundle.getnames())==set(receipt['members'])
        for name,item in receipt['members'].items():
            data=bundle.extractfile(name).read()
            assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
links=0;broken=[]
for name,data in entries.items():
    assert not name.startswith('/') and '..' not in Path(name).parts and not any(p in SKIP for p in Path(name).parts)
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
assert summary['test_count']==479 and summary['runtime_test_build_inputs_matched_across_final_stages']==len(validated)==157
for name,digest in summary['reference_sha256'].items():assert hashlib.sha256(entries['references/'+name]).hexdigest()==digest
apk_bundle=OUT/summary['apk_bundle']['filename'];assert hashlib.sha256(apk_bundle.read_bytes()).hexdigest()==summary['apk_bundle']['sha256']
with tarfile.open(apk_bundle,'r:xz') as bundle:
    for item in summary['artifacts']:
        data=bundle.extractfile(item['filename']).read();assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
rust={n:hashlib.sha256(data).hexdigest() for n,data in entries.items() if n.startswith(('crates/','apps/windows-agent/')) or n in {'Cargo.lock','Cargo.toml','rust-toolchain.toml'}}
manifest={'format':1,'scope':'Android Phase 4 development source and selected measured evidence; unchanged actual Rust peer source','validated_android_input_count':len(validated),'current_host_interop_tests_passed':479,'native_execution':False,'ci_executed':False,'rust_peer_source_sha256':rust,'files':{n:{'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'origin':origins[n]} for n,data in sorted(entries.items())}}
entries['SOURCE-MANIFEST.json']=(json.dumps(manifest,indent=2)+'\n').encode()
archive=OUT/(PREFIX+'_Source.tar.xz');assert not archive.exists(),'Never overwrite a published source delivery'
# A shared 64 MiB XZ dictionary also removes repeated unchanged historical host-image bytes.
with tarfile.open(archive,'x:xz',preset=9) as bundle:
    for name,data in sorted(entries.items()):
        info=tarfile.TarInfo(PREFIX+'/'+name);info.size=len(data);info.mtime=1793404800
        info.mode=0o755 if name.endswith(('.py','.sh')) or name.endswith('/gradlew') else 0o644
        info.uid=info.gid=0;info.uname=info.gname='';bundle.addfile(info,io.BytesIO(data))
with tarfile.open(archive,'r:xz') as bundle:
    assert set(bundle.getnames())=={PREFIX+'/'+n for n in entries}
    for name,data in entries.items():assert bundle.extractfile(PREFIX+'/'+name).read()==data
archive_hash=hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_name(archive.name+'.sha256').write_text(archive_hash+'  '+archive.name+'\n')
checksums=[hashlib.sha256((OUT/name).read_bytes()).hexdigest()+'  '+name for name in (archive.name,apk_bundle.name)]
checksums.extend(x['sha256']+'  '+x['filename'] for x in summary['artifacts'])
(OUT/(PREFIX+'_SHA256SUMS.txt')).write_text('\n'.join(checksums)+'\n')
report={'archive':archive.name,'format':'tar.xz','bytes':archive.stat().st_size,'sha256':archive_hash,'files':len(entries),'markdown_documents':sum(n.endswith('.md') for n in entries),'local_markdown_links_checked':links,'validated_android_inputs_matched':len(validated),'historical_frozen_files_unchanged':len(historical),'historical_log_html_members_verified':history_result['relocated_log_or_html_members'],'legacy_zip_members_roundtrip_verified':len(relocation['members']),'rust_peer_source_files':len(rust),'original_reference_assets_matched':len(summary['reference_sha256']),'production_private_keys_profiles_or_sdk_caches_included':False,'archive_roundtrip_and_member_bytes_verified':True,'executable_modes_preserved':True,'desktop_historical_tests_rerun':False,'native_tests_executed':False,'remote_ci_executed':False,'note':'Final host-03 renders included. Exact preparation logs included. Verbose DEX and earlier-run/render containers remain in workspace with receipts; older full histories are separate preserved deliveries. XZ selected to preserve exact bytes within the workspace budget.'}
(OUT/(PREFIX+'_Package_Check.json')).write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
