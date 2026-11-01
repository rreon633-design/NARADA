#!/usr/bin/env python3
"""Package current source/selected evidence, preserving original desktop overlays.
No historical validator or device is executed; private profiles/keys/caches excluded.
"""
import ast, hashlib, io, json, posixpath, re, tarfile
from pathlib import Path
from urllib.parse import unquote, urlsplit
from android_phase67_history import historical_bytes, verify_historical, known_evidence_paths
from android_phase67_inputs import android_inputs, desktop_inputs, sha
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT.parent;E=ROOT/'artifacts/android-phase-6-7';PREFIX='NARADA_Android_Phase6-7'
SKIP={'build','.cache','.gradle','.kotlin','.git','.idea','node_modules','__pycache__','.venv','target','out','dist','coverage','.historical-runtime','.narada-data'}
LARGE={'dex-packages.txt.gz','dex-packages.txt.xz'}
summary=json.loads((E/'validation-summary.json').read_text());final=set(summary['final_stage_records'])
history=json.loads((E/'historical-input-hashes.json').read_text());known=set(history)|known_evidence_paths(ROOT)
originals=set(summary['historical_verification']['explicitly_superseded_live_paths'])
omitted={'references/'+n for n in summary['reference_sha256'] if n.endswith('.png')};assert len(omitted)==3
entries={};origins={}
def include(path):
    rel=path.relative_to(ROOT);name=rel.as_posix()
    if path.is_symlink() or any(p in SKIP for p in rel.parts) or name in omitted:return
    if path.name in {'local.properties','.DS_Store'}|LARGE or path.name.endswith('.tar.xz') or path.suffix in {'.jks','.keystore','.apk','.idsig','.p12','.pfx','.key','.bsdiff'}:return
    if '/junit/binary/' in name:return
    if name.startswith('artifacts/android-phase-6-7/preparation/package-') and path.suffix=='.log':return
    if path.is_file():data=path.read_bytes()
    elif name in known:data=historical_bytes(ROOT,name)
    else:return
    entries[name]=data;origins[name]=name
for folder in ('apps/android','docs/android','crates','apps/windows-agent','apps/desktop-shell','desktop-ui','config','release','tests/corpus'):
    for path in sorted((ROOT/folder).rglob('*')):include(path)
for name in desktop_inputs(ROOT):include(ROOT/name)
for path in sorted((ROOT/'scripts').iterdir()):
    if path.is_file() and path.suffix in {'.py','.sh','.ps1','.mjs','.js','.cjs'}:include(path)
evidence={p.relative_to(ROOT).as_posix() for p in E.rglob('*') if p.is_file()}|{n for n in known if n.startswith('artifacts/android-phase-6-7/')}
for name in sorted(evidence):
    parts=Path(name).parts
    if len(parts)>3 and parts[2]=='runs' and parts[3] not in final:
        if Path(name).name not in {'summary.json','source-hashes.json','desktop-source-hashes.json','native-age-build-receipt.json','native-olm-build-receipt.json','peer-build-receipt.json','agent-binary-sha256.txt'}:continue
    include(ROOT/name)
# Needed pinned restoration inputs and selected prior summaries, not full old campaigns.
for phase,names in {
 'android-phase-1':['validation-summary.json','validated-input-hashes.json','preparation/tool-provisioning.json'],
 'android-phase-2':['validation-summary.json','validated-input-hashes.json','preparation/rust-toolchain.json'],
 'android-phase-3':['validation-summary.json','validated-input-hashes.json'],
 'android-phase-4':['validation-summary.json','validated-input-hashes.json'],
 'android-phase-5':['validation-summary.json','validated-input-hashes.json','native-dependencies.json'],
}.items():
    for name in names:include(ROOT/'artifacts'/phase/name)
for name in ('Cargo.toml','Cargo.lock','rust-toolchain.toml','package.json','package-lock.json','android-plan.md','.github/workflows/android-foundation.yml','ANDROID-PHASE1-HANDOFF.md','ANDROID-PHASE2-HANDOFF.md','ANDROID-PHASE3-HANDOFF.md','ANDROID-PHASE4-HANDOFF.md','ANDROID-PHASE5-HANDOFF.md','ANDROID-PHASE6-7-HANDOFF.md'):
    include(ROOT/name)
for path in sorted((ROOT/'references').glob('*.html')):include(path)
# Preserve exact pre-extension versions alongside, never substitute them for current code.
overlays={}
for name in sorted(originals):
    data=historical_bytes(ROOT,name);assert hashlib.sha256(data).hexdigest()==history[name]
    dest='preserved-desktop-before-extension/'+name;entries[dest]=data;origins[dest]='original pre-extension '+name
    overlays[name]={'original_sha256':history[name],'current_sha256':sha(ROOT/name),'preserved_member':dest}
entries['preserved-desktop-before-extension/README.md']=b'''# Original desktop overlay bytes

These seven files are exact pre-extension versions, not the current implementation. Current source remains in the ordinary project paths. The versioned upload extension was explicitly authorized; the historical desktop single-pass campaign was never rerun or relabeled. This overlay is not a new passing desktop validation result. Complete prior source archives/evidence remain separately preserved.
'''
origins['preserved-desktop-before-extension/README.md']='generated original-overlay explanation'
assets={}
for name,digest in summary['reference_sha256'].items():
    path=ROOT/'references'/name;assert sha(path)==digest
    assets[name]={'sha256':digest,'bytes':path.stat().st_size,'included':name.endswith('.html'),'preserved_locations':['full workspace: NARADA/references/'+name,'unchanged Phase4 source archive: NARADA_Android_Phase4/references/'+name]}
entries['references/ASSETS.json']=(json.dumps({'assets':assets,'note':'Three unchanged PNGs are optional original design inputs, not build/runtime dependencies; exact originals remain separately preserved.'},indent=2)+'\n').encode();origins['references/ASSETS.json']='generated original-reference integrity manifest'
entries['references/README.md']=b'''# Original design references

[Android plan](../android-plan.md) / [asset manifest](ASSETS.json). Both original HTML references are included unchanged: [Android](original-android-prototype.html), [desktop](original-desktop-prototype.html). They are visual references, not production data or a WebView implementation.

The three original PNGs are deliberately not duplicated: preserve them from the full workspace or the unchanged Phase4 source archive at the exact locations/hashes in ASSETS.json. They are optional design inputs, not missing Android runtime/build assets. Current host screenshots are separately labeled synthetic, not device captures.
''';origins['references/README.md']='generated compact-reference explanation'
entries['README.md']=('''# NARADA — Android Phases 6 & 7 source

Extract this tar.xz container first. Start with [the handoff](ANDROID-PHASE6-7-HANDOFF.md), [build/tech stack](apps/android/README.md), [split documentation](docs/android/README.md) and [roadmap](android-plan.md). The standalone APK tar.xz is a separate download; extract it before installation.

Included: all223 measured Android input files; both Rust/JNI adapters, exact locks/notices/upstream supplements; current Rust peer/Desktop UI/config/release inputs; the seven exact pre-extension desktop files under `preserved-desktop-before-extension`; build/test/audit/CI source; final selected logs/XML/host renders and earlier summaries/fingerprints. Current desktop frontend source inclusion is not a rerun of its historical validator or Windows qualification. Scripts for past campaigns are historical source, not authorization to rerun/relabel them.

**796 current development checks passed.** Lint and both APK builds/static/native-provenance audit passed.13 instrumentation cases compiled,0 executed. Android/emulator/Windows/remote-CI execution and independent crypto/JNI/storage/advisory/legal review remain open. Source/package integrity is not release approval.

**Compact reference/evidence policy:** both original HTML prototypes and [five-asset hash/location manifest](references/ASSETS.json) are included. The three unchanged original reference PNGs, full historical preservation containers and verbose DEX inventory remain separately in the full workspace/older source deliveries. They are not omitted runtime inputs. Current selected source/evidence bytes are included directly; full historical-reader/audit commands require the separately preserved containers. Ordinary current compilation and host/actual-peer tests use the included sources plus the pinned tool dependencies.

The supplied development signer differs from Phase5; no in-place/data migration is qualified. Use a fresh disposable profile; never erase valued data to bypass an install/storage conflict. No SDK/dependency cache, private runtime profile, signing/identity/content key or APK is included in this source archive. Generated native outputs are rebuilt from source. See [workflow](docs/android/phase-6-7-workflow.md), [native gates](docs/android/phase-6-7-device-checklist.md) and [artifact recovery](docs/android/artifact-recovery.md).
''').encode();origins['README.md']='generated standalone source index'

def targets(name,data):
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',data.decode()):
        target=target.split(' "')[0].strip('<>')
        if urlsplit(target).scheme or target.startswith('#'):continue
        rel=unquote(urlsplit(target).path)
        if rel:yield target,posixpath.normpath(posixpath.join(posixpath.dirname(name),rel))
for _ in range(20):
    before=len(entries)
    for name,data in list(entries.items()):
        if not name.endswith('.md'):continue
        for target,resolved in targets(name,data):
            if resolved in entries or resolved in omitted or any(n.startswith(resolved.rstrip('/')+'/') for n in entries):continue
            assert not resolved.startswith(('/','../')),('Outside-bundle link',name,target)
            source=ROOT/resolved
            if source.is_file() or resolved in known:include(source)
            elif source.is_dir() or any(n.startswith(resolved.rstrip('/')+'/') for n in known):
                for path in sorted(source.rglob('*')):include(path)
                for preserved in sorted(n for n in known if n.startswith(resolved.rstrip('/')+'/')):include(ROOT/preserved)
    if len(entries)==before:break
validated=json.loads((E/'validated-input-hashes.json').read_text());assert validated==android_inputs(ROOT)
for name,digest in validated.items():assert hashlib.sha256(entries[name]).hexdigest()==digest,('Missing/changed Android input',name)
desktop=json.loads((E/'validated-desktop-input-hashes.json').read_text());assert desktop==desktop_inputs(ROOT)
for name,digest in desktop.items():assert hashlib.sha256(entries[name]).hexdigest()==digest,('Missing/changed desktop input',name)
history_result=verify_historical(ROOT,history)
links=0;optional=[];broken=[]
for name,data in entries.items():
    assert not name.startswith('/') and '..' not in Path(name).parts and not any(p in SKIP for p in Path(name).parts)
    assert not re.search(r'(?i)(\.keystore|\.jks|\.p12|\.pfx|\.key)$',name)
    if name in history and name not in originals:assert hashlib.sha256(data).hexdigest()==history[name],('Changed historical member',name)
    if name.endswith('.py'):ast.parse(data.decode(),filename=name)
    if name.endswith(('.kt','.kts','.py','.md','.yml','.toml','.properties','.xml','.rs','.js','.mjs')):assert not re.search(rb'(?m)^-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\r?$',data),name
    if name.endswith('.md'):
        for target,resolved in targets(name,data):
            if resolved in omitted:optional.append({'document':name,'target':resolved});continue
            links+=1
            if resolved not in entries and not any(n.startswith(resolved.rstrip('/')+'/') for n in entries):broken.append((name,target,resolved))
assert not broken,broken
assert summary['test_count']==796 and len(validated)==223 and len(desktop)==101
apk_bundle=OUT/summary['apk_bundle']['filename'];assert sha(apk_bundle)==summary['apk_bundle']['sha256']
with tarfile.open(apk_bundle,'r:xz') as t:
    for item in summary['artifacts']:
        data=t.extractfile(item['filename']).read();assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
manifest={'format':1,'scope':'Current Android Phases6/7 source and selected evidence, current authorized desktop extension plus exact original overlays; full historical containers and optional original reference PNGs remain separate.','validated_android_input_count':len(validated),'desktop_input_count':len(desktop),'final_development_checks_passed':796,'android_execution':False,'ci_executed':False,'independent_review':False,'original_desktop_overlays':overlays,'external_original_design_assets':{n:assets[Path(n).name] for n in sorted(omitted)},'optional_design_links':optional,'files':{n:{'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'origin':origins[n]} for n,data in sorted(entries.items())}}
entries['SOURCE-MANIFEST.json']=(json.dumps(manifest,indent=2)+'\n').encode()
archive=OUT/(PREFIX+'_Source.tar.xz');assert not archive.exists(),'Do not overwrite a published source delivery'
with tarfile.open(archive,'x:xz',preset=9) as t:
    for name,data in sorted(entries.items()):
        info=tarfile.TarInfo(PREFIX+'/'+name);info.size=len(data);info.mtime=1793491200;info.mode=0o755 if name.endswith(('.py','.sh')) or name.endswith('/gradlew') else 0o644;info.uid=info.gid=0;info.uname=info.gname='';t.addfile(info,io.BytesIO(data))
with tarfile.open(archive,'r:xz') as t:
    assert set(t.getnames())=={PREFIX+'/'+n for n in entries}
    for name,data in entries.items():assert t.extractfile(PREFIX+'/'+name).read()==data
archive.with_name(archive.name+'.sha256').write_text(sha(archive)+'  '+archive.name+'\n')
sums=[sha(OUT/n)+'  '+n for n in (archive.name,apk_bundle.name,'NARADA_Recover_APKs.py','NARADA_Android_Phase4_APKs_Recovery.json','NARADA_Android_Phase4_APKs_Recovery.bsdiff','NARADA_Android_Phase5_APKs_Recovery.json','NARADA_Android_Phase5_APKs_Recovery.bsdiff')]
sums += [v['sha256']+'  '+v['filename'] for v in summary['artifacts']]
(OUT/(PREFIX+'_SHA256SUMS.txt')).write_text('\n'.join(sums)+'\n')
report={'archive':archive.name,'format':'tar.xz','bytes':archive.stat().st_size,'sha256':sha(archive),'files':len(entries),'markdown_documents':sum(n.endswith('.md') for n in entries),'internal_markdown_links_checked':links,'broken_nonreference_links':0,'optional_original_design_links':optional,'validated_android_inputs_matched':len(validated),'desktop_inputs_matched':len(desktop),'original_desktop_overlay_files':len(overlays),'historical_verification':history_result,'original_reference_assets_verified':len(assets),'reference_html_assets_included':2,'original_reference_pngs_preserved_separately':3,'private_keys_profiles_caches_or_apks_in_source':False,'archive_roundtrip_and_member_bytes_verified':True,'executable_modes_preserved':True,'historical_desktop_tests_rerun':False,'android_tests_executed':False,'remote_ci_executed':False,'package_script_sha256':sha(Path(__file__)),'note':'All223 measured Android inputs and101 current desktop inputs included; exact7 original overlays retained. Selected final logs/XML/renders included directly. Full historical containers/verbose DEX inventory/optional original PNGs are separate; no runtime input omitted.'}
(OUT/(PREFIX+'_Package_Check.json')).write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
