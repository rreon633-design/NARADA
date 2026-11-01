#!/usr/bin/env python3
"""Package the measured Android Phase 2 source and selected evidence; no build/test execution.
Historical delivered archives/results are verified, never overwritten.
"""
import ast, hashlib, json, posixpath, re, zipfile
from pathlib import Path
from urllib.parse import unquote, urlsplit
ROOT=Path(__file__).resolve().parents[1]; OUT=ROOT.parent
PREFIX='NARADA_Android_Phase2'
SKIP={'build','.cache','.gradle','.kotlin','.git','.idea','node_modules','__pycache__','.venv','target','out','dist','coverage'}
entries={};origins={}
def include(path):
    if not path.is_file() or path.is_symlink():return
    rel=path.relative_to(ROOT)
    if any(part in SKIP for part in rel.parts):return
    if path.name in {'local.properties','.DS_Store'} or path.suffix in {'.jks','.keystore','.apk','.idsig'}:return
    name=rel.as_posix();entries[name]=path.read_bytes();origins[name]=name
for folder in ('apps/android','docs/android','crates','apps/windows-agent','apps/desktop-shell/src-tauri','tests/corpus'):
    for p in sorted((ROOT/folder).rglob('*')):include(p)
# Preserve final run logs, XML and renders plus earlier failures/receipts. Earlier render sets
# and huge verbose inventories stay in the workspace; their hashes/receipts remain in-package.
for p in sorted((ROOT/'artifacts/android-phase-2').rglob('*')):
    name=p.relative_to(ROOT).as_posix()
    if p.name=='dex-packages.txt.gz':continue
    if '/host-renders/' in name and '/runs/host-03/' not in name:continue
    include(p)
# Small, unchanged historical receipts linked by the Phase 1 documentation; its full campaign
# and source remain in the separately preserved Phase 1 source archive/workspace.
for name in ('validation-summary.json','validated-input-hashes.json','apk-audit/AndroidManifest.xml','apk-audit/signature.txt','apk-audit/instrumentation-signature.txt','apk-audit/zipalign.txt','runs/host-05/summary.json','runs/lint-02/lint-results-debug.xml'):
    include(ROOT/'artifacts/android-phase-1'/name)
for name in ('Cargo.toml','Cargo.lock','rust-toolchain.toml','android-plan.md','ANDROID-PHASE1-HANDOFF.md','ANDROID-PHASE2-HANDOFF.md','.github/workflows/android-foundation.yml','scripts/validate_android_phase2.py','scripts/android_memory_guard.py','scripts/audit_android_phase2.py','scripts/package_android_phase2.py'):
    include(ROOT/name)
for p in sorted((ROOT/'references').iterdir()):
    if p.suffix.lower() in {'.png','.html'}:include(p)
entries['README.md']=('# NARADA — Android Phase 2 source\n\nOpen `apps/android` in Android Studio. Follow its README for JDK/SDK/Gradle and the optional real Rust-peer interoperability build. This is native Android development source, not a WebView or desktop-UI bundle. APKs are delivered separately in a lossless ZIP.\n\nThe Rust workspace/peer source is unchanged and included for `cargo build -p narada-agent`; the complete desktop frontend/history remains in the separate desktop bundle. Selected Phase 1 receipts are included; its full evidence remains in its own preserved source archive. Final Phase 2 logs/XML/renders and earlier failures/receipts are included. Verbose DEX inventories and superseded render sets remain in the workspace; inventory hashes/receipts and reproducible inspection commands are included. No private profiles or signing keys are packaged.\n\n'+(ROOT/'ANDROID-PHASE2-HANDOFF.md').read_text()).encode()
origins['README.md']='generated package index + current Android handoff'
entries['references/README.md']='''# Original supplied visual references

[Android plan](../android-plan.md) · [Native architecture](../docs/android/architecture.md)

The five image/HTML files are byte-identical copies of the supplied assets. Their old branding, sample devices/messages/progress and prototype scripts are references, not production behavior.

- [Android screenshot](android-screens-reference.png) — mobile visual direction.
- [Android HTML](original-android-prototype.html) — supplied `IFB_Android_Mobile_All_Screens.html`, not app code.
- [Desktop Files](desktop-files-reference.png) and [desktop overview](desktop-screens-reference.png) — preserved desktop references.
- [Desktop HTML](original-desktop-prototype.html) — supplied `IFB_Desktop_All_16_Screens.html`.

NARADA implements native, truthful states and keeps content features unavailable in Android Phase 2. The full original desktop documentation is separate.
'''.encode();origins['references/README.md']='generated standalone index; original assets unchanged'
validated=json.loads((ROOT/'artifacts/android-phase-2/validated-input-hashes.json').read_text())
for name,digest in validated.items():
    assert hashlib.sha256(entries[name]).hexdigest()==digest,('validated source mismatch',name)
# All historical input files and old source archives remain byte-identical, irrespective of
# which are selected for this standalone Android delivery.
historical=json.loads((ROOT/'artifacts/android-phase-2/historical-input-hashes.json').read_text())
for name,digest in historical.items():
    p=ROOT/name;assert p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==digest,('historical change',name)
for filename,digest in {'NARADA_Desktop_5-9_Source.zip':'d6b15bc1fbcc028bc50407453c5ad37bd8e381c9ae11fe4369610a728acd892d','NARADA_Android_Phase1_Source.zip':'eef65d405a1a008e896da44c4b562517f25c8a266d5f4f53ab593941df3bfbff'}.items():
    assert hashlib.sha256((OUT/filename).read_bytes()).hexdigest()==digest
for name,data in entries.items():
    assert not name.startswith('/') and '..' not in Path(name).parts
    assert not re.search(r'(?i)(\.keystore|\.jks|\.p12|\.pfx|\.key)$',name),name
    if name.endswith('.py'):ast.parse(data.decode(),filename=name)
    if name.endswith(('.kt','.kts','.py','.md','.yml','.toml','.properties','.xml','.rs')):
        assert not re.search(rb'(?m)^-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\r?$',data),name
links=0;broken=[]
for name,data in entries.items():
    if not name.endswith('.md'):continue
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',data.decode()):
        target=target.split(' "')[0].strip('<>')
        if urlsplit(target).scheme or target.startswith('#'):continue
        rel=unquote(urlsplit(target).path)
        if not rel:continue
        resolved=posixpath.normpath(posixpath.join(posixpath.dirname(name),rel));links+=1
        if resolved not in entries and not any(n.startswith(resolved.rstrip('/')+'/') for n in entries):broken.append((name,target,resolved))
assert not broken,broken
summary=json.loads((ROOT/'artifacts/android-phase-2/validation-summary.json').read_text())
for name,digest in summary['reference_sha256'].items():assert hashlib.sha256(entries['references/'+name]).hexdigest()==digest
rust={n:hashlib.sha256(data).hexdigest() for n,data in entries.items() if n.startswith(('crates/','apps/windows-agent/')) or n in {'Cargo.lock','Cargo.toml','rust-toolchain.toml'}}
manifest={'format':1,'scope':'Android Phase 2 development source and selected measured evidence; real unchanged Rust peer source for interop','validated_android_input_count':len(validated),'current_host_interop_tests_passed':186,'native_execution':False,'ci_executed':False,'rust_peer_source_sha256':rust,'files':{n:{'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'origin':origins[n]} for n,data in sorted(entries.items())}}
entries['SOURCE-MANIFEST.json']=(json.dumps(manifest,indent=2)+'\n').encode()
archive=OUT/'NARADA_Android_Phase2_Source.zip'
assert not archive.exists(),'Never overwrite a published source bundle; choose a new delivery name'
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
checksums=[]
for filename in ('NARADA_Android_Phase2_Source.zip','NARADA_Android_Phase2_APKs.zip'):
    p=OUT/filename;checksums.append(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+filename)
checksums.extend(x['sha256']+'  '+x['filename'] for x in summary['artifacts'])
(OUT/'NARADA_Android_Phase2_SHA256SUMS.txt').write_text('\n'.join(checksums)+'\n')
report={'archive':archive.name,'bytes':archive.stat().st_size,'sha256':ziphash,'files':len(entries),'markdown_documents':sum(n.endswith('.md') for n in entries),'local_markdown_links_checked':links,'validated_android_inputs_matched':len(validated),'historical_frozen_files_unchanged':len(historical),'rust_peer_source_files':len(rust),'original_reference_assets_matched':len(summary['reference_sha256']),'production_private_keys_profiles_or_sdk_caches_included':False,'zip_crc_and_member_bytes_verified':True,'desktop_historical_tests_rerun':False,'native_tests_executed':False,'remote_ci_executed':False,'note':'Final host-03 renders included. Verbose gzip DEX inventories and superseded render sets stay in workspace; their receipts are included. Historical Phase 1 full evidence is separate.'}
(OUT/'NARADA_Android_Phase2_Package_Check.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
