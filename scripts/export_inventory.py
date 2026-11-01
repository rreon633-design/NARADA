#!/usr/bin/env python3
"""Lockfile inventory, declared licenses and source provenance, not certification."""
import base64, hashlib, json, os, shutil, subprocess, tomllib, uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts/desktop-5-9'
OUT.mkdir(parents=True,exist_ok=True)
lock=tomllib.loads((ROOT/'Cargo.lock').read_text())
metadata=subprocess.run(['cargo','metadata','--locked','--format-version','1'],cwd=ROOT,capture_output=True,text=True,check=True)
packages=json.loads(metadata.stdout)['packages']
lookup={(p['name'],p['version']):p for p in packages}
components=[];notice=[];unresolved=[];license_files=[]
licenses=OUT/'license-texts';licenses.mkdir(exist_ok=True)
for record in lock['package']:
    name,version=record['name'],record['version'];p=lookup.get((name,version),{});declared=p.get('license')
    component={'type':'library','name':name,'version':version,'purl':f'pkg:cargo/{quote(name)}@{quote(version)}','bom-ref':f'cargo:{name}:{version}'}
    if record.get('checksum'):component['hashes']=[{'alg':'SHA-256','content':record['checksum']}]
    if declared:component['licenses']=[{'expression':declared}]
    else:unresolved.append('cargo:'+name+'@'+version)
    components.append(component);notice.append(f'| Cargo | {name} | {version} | {declared or "NOASSERTION"} |')
    manifest=p.get('manifest_path')
    if manifest and record.get('source'):
        folder=Path(manifest).parent.resolve();candidates=list(folder.glob('LICENSE*'))+list(folder.glob('COPYING*'))+list(folder.glob('NOTICE*'))
        if p.get('license_file'):candidates.append(folder/p['license_file'])
        for path in set(candidates):
            resolved=path.resolve()
            if not resolved.is_relative_to(folder) or not resolved.is_file() or resolved.stat().st_size>131072:continue
            target=licenses/f'cargo-{name}-{version}-{resolved.name}.txt';target.write_text(resolved.read_text(errors='replace'))
            license_files.append({'package':f'cargo:{name}:{version}','file':target.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
node=json.loads((ROOT/'package-lock.json').read_text())
seen=set()
for location,p in node.get('packages',{}).items():
    if 'node_modules/' not in location or not p.get('version'):continue
    name=p.get('name') or location.rsplit('node_modules/',1)[1];version=p['version']
    if (name,version) in seen:continue
    seen.add((name,version));declared=p.get('license');component={'type':'library','name':name,'version':version,'purl':f'pkg:npm/{quote(name,safe="/")}@{quote(version)}','bom-ref':f'npm:{name}:{version}'}
    if declared:component['licenses']=[{'expression':declared}]
    else:unresolved.append('npm:'+name+'@'+version)
    integrity=p.get('integrity','')
    if integrity.startswith('sha512-'):
        try:component['hashes']=[{'alg':'SHA-512','content':base64.b64decode(integrity[7:],validate=True).hex()}]
        except ValueError:pass
    components.append(component);notice.append(f'| npm | {name} | {version} | {declared or "NOASSERTION"} |')
    directory=(ROOT/location).resolve()
    if directory.is_relative_to(ROOT/'node_modules'):
        for path in list(directory.glob('LICENSE*'))+list(directory.glob('COPYING*')):
            resolved=path.resolve()
            if resolved.is_relative_to(directory) and resolved.is_file() and resolved.stat().st_size<=131072:
                target=licenses/f'npm-{name.replace("/","-")}-{version}-{resolved.name}.txt';target.write_text(resolved.read_text(errors='replace'));license_files.append({'package':f'npm:{name}:{version}','file':target.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
version=tomllib.loads((ROOT/'Cargo.toml').read_text())['workspace']['package']['version']
bom={'bomFormat':'CycloneDX','specVersion':'1.6','serialNumber':'urn:uuid:'+str(uuid.uuid4()),'version':1,'metadata':{'timestamp':datetime.now(timezone.utc).isoformat(),'component':{'type':'application','name':'NARADA','version':version},'properties':[{'name':'narada:scope','value':'Lockfile inventory including development/target dependencies; not an exact shipped-binary SBOM.'},{'name':'narada:release-qualified','value':'false'}]},'components':components}
(OUT/'sbom.cdx.json').write_text(json.dumps(bom,indent=2)+'\n')
pages=[]
for offset in range(0,len(notice),200):
    name=f'dependency-licenses-{offset//200+1}.md';pages.append(name)
    (OUT/name).write_text('# NARADA — Declared dependency licenses, part '+str(offset//200+1)+'\n\n[Inventory index](dependency-licenses.md). Declarations are not legal approval.\n\n| Ecosystem | Dependency | Version | Declared license |\n|---|---|---|---|\n'+'\n'.join(notice[offset:offset+200])+'\n')
(OUT/'dependency-licenses.md').write_text('# NARADA — Dependency inventory index\n\nGenerated from current lockfiles, including development/target dependencies. Not an exact shipped-installer SBOM or legal approval. License text copies are listed in supply-chain.json.\n\n'+'\n'.join(f'- [Declarations, part {i+1}]({name})' for i,name in enumerate(pages))+'\n\n## Unresolved declarations\n\n'+('\n'.join('- '+s for s in unresolved) if unresolved else 'None in the metadata available to this generator.')+'\n')

result={'application':'NARADA','version':version,'components':len(components),'license_texts':license_files,'unresolved_declarations':unresolved,'release_approved':False,'signed':False,'source_lockfiles':{n:hashlib.sha256((ROOT/n).read_bytes()).hexdigest() for n in ['Cargo.lock','package-lock.json']},'upstream_crypto':{'vodozemac':'0.11.1 exact-pinned; upstream historical audit is not NARADA certification','age':'0.12.1 exact-pinned; upstream pre-1.0 testing/beta warning remains a release gate'}}
(OUT/'supply-chain.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'components':len(components),'copied_license_texts':len(license_files),'unresolved_declarations':len(unresolved),'release_approved':False},indent=2))
