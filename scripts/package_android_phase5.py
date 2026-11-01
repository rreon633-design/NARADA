#!/usr/bin/env python3
"""Package measured Phase 5 source/selected evidence; no tests or release approval.
Frozen historical bytes remain exact. Full preservation containers stay separate.
Three unchanged reference PNGs are explicit optional design inputs, not build dependencies.
"""
import ast,hashlib,io,json,posixpath,re,tarfile
from pathlib import Path
from urllib.parse import unquote,urlsplit
from android_phase5_history import historical_bytes,verify_historical,known_evidence_paths
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT.parent
PREFIX='NARADA_Android_Phase5';E=ROOT/'artifacts/android-phase-5'
SKIP={'build','.cache','.gradle','.kotlin','.git','.idea','node_modules','__pycache__','.venv','target','out','dist','coverage','.historical-runtime','.narada-data'}
LARGE={'dex-packages.txt.gz','dex-packages.txt.xz'}
historical=json.loads((E/'historical-input-hashes.json').read_text())
known=set(historical)|known_evidence_paths(ROOT)
summary=json.loads((E/'validation-summary.json').read_text());final=set(summary['final_stage_records'])
omitted={'references/'+n for n in summary['reference_sha256'] if n.endswith('.png')}
assert len(omitted)==3
entries={};origins={}
def include(path):
    if path.is_symlink():return
    rel=path.relative_to(ROOT);name=rel.as_posix()
    if any(part in SKIP for part in rel.parts) or name in omitted:return
    if path.name in {'local.properties','.DS_Store'}|LARGE or path.name.endswith('.tar.xz') or path.suffix in {'.jks','.keystore','.apk','.idsig','.p12','.pfx','.key'}:return
    if '/junit/binary/' in name:return
    if path.is_file():data=path.read_bytes()
    elif name in known:data=historical_bytes(ROOT,name)
    else:return
    entries[name]=data;origins[name]=name

for folder in ('apps/android','docs/android','crates','apps/windows-agent','apps/desktop-shell/src-tauri','tests/corpus'):
    for path in sorted((ROOT/folder).rglob('*')):include(path)
evidence_names={p.relative_to(ROOT).as_posix() for p in E.rglob('*') if p.is_file()}|{n for n in known if n.startswith('artifacts/android-phase-5/')}
for name in sorted(evidence_names):
    parts=Path(name).parts
    if len(parts)>3 and parts[2]=='runs' and parts[3] not in final:
        if Path(name).name not in {'summary.json','source-hashes.json','native-build-receipt.json','agent-binary-sha256.txt'}:continue
    include(ROOT/name)
# Pinned restoration recipes and selected prior evidence; full histories are separate.
for phase,names in {
    'android-phase-1':('validation-summary.json','validated-input-hashes.json','preparation/tool-provisioning.json','apk-audit/AndroidManifest.xml','apk-audit/signature.txt','apk-audit/instrumentation-signature.txt','apk-audit/zipalign.txt','runs/host-05/summary.json','runs/lint-02/lint-results-debug.xml'),
    'android-phase-2':('validation-summary.json','validated-input-hashes.json','historical-input-hashes.json','preparation/rust-toolchain.json','apk-audit-02/AndroidManifest.xml','apk-audit-02/signature.txt','apk-audit-02/instrumentation-signature.txt','apk-audit-02/zipalign.txt','apk-audit-02/dex-inventory-receipt.json','runs/host-03/summary.json','runs/interop-03/summary.json','runs/lint-03/lint-results-debug.xml'),
    'android-phase-3':('validation-summary.json','validated-input-hashes.json','historical-input-hashes.json','apk-audit/AndroidManifest.xml','apk-audit/signature.txt','apk-audit/instrumentation-signature.txt','apk-audit/zipalign.txt','apk-audit/dex-inventory-receipt.json','runs/host-03/summary.json','runs/interop-02/summary.json','runs/lint-01/lint-results-debug.xml'),
    'android-phase-4':('validation-summary.json','validated-input-hashes.json','historical-input-hashes.json','apk-audit/AndroidManifest.xml','apk-audit/signature.txt','apk-audit/instrumentation-signature.txt','apk-audit/zipalign.txt','apk-audit/dex-inventory-receipt.json','runs/host-03/summary.json','runs/interop-02/summary.json','runs/lint-03/lint-results-debug.xml','preparation/historical-archive-relocation.json','preparation/historical-log-relocation.json','preparation/earlier-validation-runs.json','preparation/preparation-logs.json'),
}.items():
    for name in names:include(ROOT/'artifacts'/phase/name)
for name in ('Cargo.toml','Cargo.lock','rust-toolchain.toml','android-plan.md','ANDROID-PHASE1-HANDOFF.md','ANDROID-PHASE2-HANDOFF.md','ANDROID-PHASE3-HANDOFF.md','ANDROID-PHASE4-HANDOFF.md','ANDROID-PHASE5-HANDOFF.md','.github/workflows/android-foundation.yml','scripts/validate_android_phase5.py','scripts/android_memory_guard.py','scripts/build_android_olm.py','scripts/audit_android_phase5.py','scripts/package_android_phase5.py','scripts/android_phase5_history.py','scripts/android_phase4_history.py','scripts/provision_android_phase5.py','scripts/provision_android_phase5_native.py'):
    include(ROOT/name)
for path in sorted((ROOT/'references').glob('*.html')):include(path)
assets={}
for name,digest in summary['reference_sha256'].items():
    raw=(ROOT/'references'/name).read_bytes();assert hashlib.sha256(raw).hexdigest()==digest
    assets[name]={'sha256':digest,'bytes':len(raw),'included_in_this_source_archive':name.endswith('.html'),'preserved_locations':['full workspace: NARADA/references/'+name,'unchanged NARADA_Android_Phase4_Source.tar.xz: NARADA_Android_Phase4/references/'+name]}
entries['references/ASSETS.json']=(json.dumps({'assets':assets,'note':'The three PNGs are optional original design references, not runtime/build inputs. Exact bytes remain in the full workspace and previous source delivery; do not substitute generated images.'},indent=2)+'\n').encode();origins['references/ASSETS.json']='generated five-asset integrity/location manifest'
entries['README.md']=('''# NARADA — Android Phase 5 source

Extract this tar.xz archive with tar/7-Zip or Python tarfile. Open `apps/android` in Android Studio after preparing the pinned Rust/NDK JNI libraries. Start with [build/tech stack](apps/android/README.md) and the [split documentation](docs/android/README.md). The separate APK tar.xz must also be extracted before installation.

Included: all177 measured Android runtime/test/build inputs, stateless Olm adapter/lockfile/notices, unchanged actual Rust peer/workspace source, current split docs and CI definition, final selected logs/XML/host renders, earlier summaries/fingerprints and selected prior evidence. No SDK/cache, APK, private profile or signing/identity/content key is included. CI source is included, not remotely executed.

**Compact reference policy:** both original HTML prototypes and [the five-asset SHA256/location manifest](references/ASSETS.json) are included. The three unchanged large design-reference PNGs are deliberately not duplicated. Keep them from the full workspace `references/` folder or the unchanged Phase4 source archive if you want the original design images and their older documentation links. They are NOT build/runtime dependencies; no measured Android input is omitted. The original images are preserved, not replaced by generated artwork.

Full historical/presuperseded preservation containers and verbose DEX inventories stay separately in the full workspace. Selected included historical log/XML/render paths are restored byte-for-byte. History-reader/full historical-audit commands require those original workspace containers; ordinary compilation and host/actual-peer tests use the included source and reviewed tool dependencies. This is selected development evidence, not every prior desktop campaign or the full desktop frontend.

The supplied APK signer differs from Phase4 and Phase1–3. No same-signer in-place update is qualified. Do not clear valued data to resolve a signature conflict. A rebuild needs your own controlled signer/environment. Integrity manifests do not confer independent review, Android execution or release approval.

'''+(ROOT/'ANDROID-PHASE5-HANDOFF.md').read_text()).encode();origins['README.md']='generated standalone index + current Phase5 handoff'
entries['references/README.md']='''# Original supplied visual references

[Android plan](../android-plan.md) · [Native architecture](../docs/android/architecture.md) · [Asset manifest](ASSETS.json)

Both original HTML prototypes are included unchanged: [Android](original-android-prototype.html) (`IFB_Android_Mobile_All_Screens.html`) and [desktop](original-desktop-prototype.html) (`IFB_Desktop_All_16_Screens.html`). These are design references, not app runtime code or production messages.

The three original PNGs (`android-screens-reference.png`, `desktop-files-reference.png`, `desktop-screens-reference.png`) are preserved unchanged in the full workspace and Phase4 source archive. This compact Phase5 source archive intentionally does not duplicate them. Their exact hashes/locations are in ASSETS.json. Copy those exact originals into this directory if you want to view the older documentation's design-image links. They are not required to compile or test Android.

Phase5 uses native Compose and actual authorized state; no prototype fake activity is injected. The full original reference set remains preserved, and all177 measured executable inputs are included here.
'''.encode();origins['references/README.md']='generated standalone reference manifest index; original assets not modified'

def local_targets(name,data):
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',data.decode()):
        target=target.split(' "')[0].strip('<>')
        if urlsplit(target).scheme or target.startswith('#'):continue
        rel=unquote(urlsplit(target).path)
        if rel:yield target,posixpath.normpath(posixpath.join(posixpath.dirname(name),rel))
for _ in range(16):
    before=len(entries)
    for name,data in list(entries.items()):
        if not name.endswith('.md'):continue
        for target,resolved in local_targets(name,data):
            if resolved in entries or resolved in omitted or any(n.startswith(resolved.rstrip('/')+'/') for n in entries):continue
            assert not resolved.startswith(('/','../')),('outside bundle link',name,target)
            source=ROOT/resolved
            if source.is_file() or resolved in known:include(source)
            elif source.is_dir():
                for path in sorted(source.rglob('*')):include(path)
                for preserved in sorted(n for n in known if n.startswith(resolved.rstrip('/')+'/')):include(ROOT/preserved)
    if len(entries)==before:break

validated=json.loads((E/'validated-input-hashes.json').read_text())
for name,digest in validated.items():
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,('post-audit input change',name)
    assert hashlib.sha256(entries[name]).hexdigest()==digest,('packaged validated input changed',name)
history_result=verify_historical(ROOT,historical)
for name,data in entries.items():
    if name in historical:assert hashlib.sha256(data).hexdigest()==historical[name],('packaged historical bytes changed',name)
# Verify the 12 original ZIPs, not merely their containing archive's name.
relocation=json.loads(historical_bytes(ROOT,'artifacts/android-phase-4/preparation/historical-archive-relocation.json'))
legacy=OUT/relocation['archive'];assert hashlib.sha256(legacy.read_bytes()).hexdigest()==relocation['archive_sha256']
with tarfile.open(legacy,'r:xz') as bundle:
    assert set(bundle.getnames())==set(relocation['members'])
    for member in bundle:
        data=bundle.extractfile(member).read();receipt=relocation['members'][member.name]
        assert len(data)==receipt['bytes'] and hashlib.sha256(data).hexdigest()==receipt['sha256']
# Also check retained earlier Phase4 run/preparation containers independently.
for filename in ('earlier-validation-runs.json','preparation-logs.json'):
    receipt=json.loads(historical_bytes(ROOT,'artifacts/android-phase-4/preparation/'+filename));archive=ROOT/'artifacts/android-phase-4'/receipt['archive']
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==receipt['sha256']
    with tarfile.open(archive,'r:xz') as bundle:
        assert set(bundle.getnames())==set(receipt['members'])
        for name,item in receipt['members'].items():
            data=bundle.extractfile(name).read();assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
links=0;external_reference_links=[];broken=[]
for name,data in entries.items():
    assert not name.startswith('/') and '..' not in Path(name).parts and not any(p in SKIP for p in Path(name).parts)
    assert not re.search(r'(?i)(\.keystore|\.jks|\.p12|\.pfx|\.key)$',name),name
    if name.endswith('.py'):ast.parse(data.decode(),filename=name)
    if name.endswith(('.kt','.kts','.py','.md','.yml','.toml','.properties','.xml','.rs')):
        assert not re.search(rb'(?m)^-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\r?$',data),name
    if name.endswith('.md'):
        for target,resolved in local_targets(name,data):
            if resolved in omitted:external_reference_links.append({'document':name,'target':resolved});continue
            links+=1
            if resolved not in entries and not any(n.startswith(resolved.rstrip('/')+'/') for n in entries):broken.append((name,target,resolved))
assert not broken,broken
assert summary['test_count']==581 and summary['runtime_test_build_inputs_matched_across_final_stages']==len(validated)==177
for name,digest in summary['reference_sha256'].items():
    if 'references/'+name in entries:assert hashlib.sha256(entries['references/'+name]).hexdigest()==digest
    else:assert 'references/'+name in omitted
apk_bundle=OUT/summary['apk_bundle']['filename'];assert hashlib.sha256(apk_bundle.read_bytes()).hexdigest()==summary['apk_bundle']['sha256']
with tarfile.open(apk_bundle,'r:xz') as bundle:
    for item in summary['artifacts']:
        data=bundle.extractfile(item['filename']).read();assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
rust={n:hashlib.sha256(data).hexdigest() for n,data in entries.items() if n.startswith(('crates/','apps/windows-agent/')) or n in {'Cargo.lock','Cargo.toml','rust-toolchain.toml'}}
manifest={'format':1,'scope':'Android Phase5 development source and selected evidence; unchanged actual Rust peer source; three optional original design PNGs preserved separately','validated_android_input_count':len(validated),'final_host_interop_checks_passed':581,'native_execution':False,'ci_executed':False,'external_original_design_assets':{n:assets[Path(n).name] for n in sorted(omitted)},'optional_design_image_links':external_reference_links,'rust_peer_source_sha256':rust,'files':{n:{'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'origin':origins[n]} for n,data in sorted(entries.items())}}
entries['SOURCE-MANIFEST.json']=(json.dumps(manifest,indent=2)+'\n').encode()
archive=OUT/(PREFIX+'_Source.tar.xz');assert not archive.exists(),'Never overwrite a published source delivery'
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
report={'archive':archive.name,'format':'tar.xz','bytes':archive.stat().st_size,'sha256':archive_hash,'files':len(entries),'markdown_documents':sum(n.endswith('.md') for n in entries),'internal_markdown_links_checked':links,'broken_nonreference_links':0,'optional_design_image_links_requiring_separate_originals':external_reference_links,'validated_android_inputs_matched':len(validated),'historical_frozen_files_unchanged':len(historical),'historical_files_exactly_relocated':history_result['relocated_members'],'legacy_zip_members_roundtrip_verified':len(relocation['members']),'rust_peer_source_files':len(rust),'original_reference_assets_verified':len(assets),'reference_html_assets_included':2,'original_reference_pngs_preserved_separately':3,'production_private_keys_profiles_or_sdk_caches_included':False,'archive_roundtrip_and_member_bytes_verified':True,'executable_modes_preserved':True,'desktop_historical_tests_rerun':False,'native_tests_executed':False,'remote_ci_executed':False,'note':'All177 measured inputs included; selected final logs/XML/renders restored exactly. Original reference PNGs, full preservation archives and verbose DEX inventories are separate, not duplicated. No runtime input is omitted.'}
(OUT/(PREFIX+'_Package_Check.json')).write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
