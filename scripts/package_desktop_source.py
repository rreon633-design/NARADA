#!/usr/bin/env python3
"""Package this desktop handoff, preserving failed and historical evidence.
Archive integrity is not another execution of the application test suites.
Never packages profiles, wrapping keys, identity blobs, dependencies or binaries.
"""
from pathlib import Path
import collections, hashlib, json, os, re, urllib.parse, zipfile
from source_hashes import capture
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts/desktop-5-9'
ZIP=ROOT.parent/'NARADA_Desktop_5-9_Source.zip'
MANIFEST=ROOT/'SOURCE-MANIFEST-desktop-5-9.json'
OLD=ROOT.parent/'NARADA_Phase4_Source.zip'
OLD_SHA='5d1981a092ef8ccb45b7587d136fbc422cf27dfb7ce2552f7e3ed30a15474d54'
EXCLUDED={'.git','.cache','.narada-data','target','node_modules','dist','build','out','coverage','__pycache__','gen','binaries','test-results','playwright-report','.pytest_cache','.venv','test-fixtures'}
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def files():
    result=[]
    for parent,dirs,names in os.walk(ROOT):
        dirs[:]=[d for d in dirs if d not in EXCLUDED]
        for name in names:
            p=Path(parent)/name
            if name.startswith('.env') or p.suffix in {'.db','.pem','.key','.exe','.dll','.log','.pyc'} or name.endswith(('.db-shm','.db-wal')) or name in {'transport.identity','content.guard'}:continue
            if p.is_symlink():raise ValueError('Source symlinks are not packaged: '+str(p))
            result.append(p)
    return sorted(result)
if ZIP.exists():raise SystemExit('This delivered archive already exists; do not overwrite it silently.')
report=json.loads((OUT/'validation-summary.json').read_text())
assert report['status'] in {'passed','has_failures'} and report['rerun_count']==0
assert OLD.is_file() and digest(OLD)==OLD_SHA,'Original Phase 4 archive must remain byte-for-byte unchanged'
recorded=json.loads((OUT/'tested-source-hashes.json').read_text());current=capture(ROOT)
changed={n:{'pass_source_sha256':recorded.get(n),'handoff_source_sha256':current.get(n)} for n in sorted(set(recorded)|set(current)) if recorded.get(n)!=current.get(n)}
if changed:
    corrections=json.loads((OUT/'post-pass-corrections.json').read_text())
    assert corrections['validation_rerun'] is False
    allowed={item['path']:{k:item[k] for k in ['pass_source_sha256','handoff_source_sha256']} for item in corrections['changes']}
    assert changed==allowed,'Every unverified post-pass source change must be disclosed exactly'
for name,sha in json.loads((OUT/'historical-evidence-hashes.json').read_text()).items():assert digest(ROOT/name)==sha,name
refs=[p for p in (ROOT/'references').iterdir() if p.name!='README.md'];assert len(refs)==5
protected=0
with zipfile.ZipFile(OLD) as z:
    for p in refs:assert p.read_bytes()==z.read('NARADA/'+p.relative_to(ROOT).as_posix()),p
    for name in z.namelist():
        if name.startswith('NARADA/artifacts/phase-4/') or name.startswith('NARADA/crates/narada-core/migrations/'):
            assert (ROOT/name.removeprefix('NARADA/')).read_bytes()==z.read(name),name
            protected+=1
# Placeholders allow the handoff to link to both generated package records.
if not MANIFEST.exists():MANIFEST.write_text('{}\n')
# The QA record exists before link checking so the handoff can link to it.
(OUT/'source-qa.json').write_text('{}\n')
all_files=files();markdown=[p for p in all_files if p.suffix=='.md'];broken=[];links=0
for p in markdown:
    text=re.sub(r'```[\s\S]*?```','',p.read_text())
    for href in re.findall(r'!?\[[^\]]*\]\(([^)]+)\)',text):
        href=href.strip().split(' "',1)[0]
        if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:',href) or href.startswith('#'):continue
        relative=urllib.parse.unquote(href.split('#',1)[0])
        if not relative:continue
        links+=1
        if not (p.parent/relative).resolve().exists():broken.append({'document':p.relative_to(ROOT).as_posix(),'href':href})
assert not broken,json.dumps(broken,indent=2)
json_count=0
for p in all_files:
    if p.suffix=='.json':json.loads(p.read_text());json_count+=1
    assert not (set(p.relative_to(ROOT).parts)&EXCLUDED)
counts=dict(collections.Counter(c['status'] for c in report['checks']))
qa={'status':'package_integrity_checked_not_runtime_validation','date':'2026-10-31','validation_date':report['date'],'markdown_documents':len(markdown),'checked_relative_markdown_links':links,'broken_relative_links':broken,'parseable_json_files':json_count,'unchanged_supplied_design_assets':5,'historical_phase4_archive_sha256':OLD_SHA,'protected_historical_files':protected,'validation_check_counts':counts,'validation_status':report['status'],'passed_tests':report['passed_tests'],'failed_tests':report['failed_tests'],'post_pass_source_changes':changed,'source_package_exclusions':['runtime profiles','identity blobs','wrapping/private keys','generated binaries/dependencies/caches/traces','environment and credential files'],'release_qualified':False,'note':'Only package integrity was checked here; no application suite was rerun. Relative document targets, not external sites or full anchors, were checked.'}
(OUT/'source-qa.json').write_text(json.dumps(qa,indent=2)+'\n')
all_files=[p for p in files() if p!=MANIFEST]
manifest={'application':'NARADA','source_version':'0.7.0','metadata_schema':6,'date':'2026-10-31','validation_date':report['date'],'scope':'Desktop phases 5–9 development source and evidence; Android deferred; not a signed or qualified release','validation_summary':'artifacts/desktop-5-9/validation-summary.json','validation_status':report['status'],'handoff':'DESKTOP-5-9-HANDOFF.md','post_pass_source_changes':changed,'source_file_count_including_manifest':len(all_files)+1,'manifest_excludes_its_own_hash':True,'files':{p.relative_to(ROOT).as_posix():digest(p) for p in all_files}}
MANIFEST.write_text(json.dumps(manifest,indent=2)+'\n')
all_files=files()
with zipfile.ZipFile(ZIP,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in all_files:z.write(p,arcname='NARADA/'+p.relative_to(ROOT).as_posix())
with zipfile.ZipFile(ZIP) as z:
    assert z.testzip() is None
    assert len(z.namelist())==manifest['source_file_count_including_manifest']
    for name,sha in manifest['files'].items():assert hashlib.sha256(z.read('NARADA/'+name)).hexdigest()==sha,name
sha=digest(ZIP)
ZIP.with_suffix('.zip.sha256').write_text(sha+'  '+ZIP.name+'\n')
result={'package':str(ZIP),'bytes':ZIP.stat().st_size,'sha256':sha,'files':len(all_files),'markdown_documents':len(markdown),'relative_links_checked':links,'archive_crc_and_manifest_hashes':'passed','reference_assets_unchanged':5,'historical_phase4_archive_unchanged':True,'validation_status':report['status'],'passed_tests':report['passed_tests'],'failed_tests':report['failed_tests'],'unverified_post_pass_changes':list(changed),'release_qualified':False}
(ROOT.parent/'NARADA_Desktop_5-9_Package_Check.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
