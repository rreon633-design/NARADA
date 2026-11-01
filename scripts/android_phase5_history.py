"""Read exact historical evidence, including byte-verified Phase 4/5 relocations.
No rekeying, mutation or historical test is performed by this reader.
"""
from functools import lru_cache
import hashlib
import json
import tarfile
from pathlib import Path
from android_phase4_history import historical_bytes as phase4_bytes

@lru_cache(maxsize=2)
def relocated(root_text):
    root=Path(root_text);result={}
    for name in ('pre-phase5-evidence-relocation.json','preliminary-phase5-evidence-relocation.json'):
        receipt=root/'artifacts/android-phase-5/preservation'/name
        if not receipt.is_file():continue
        record=json.loads(receipt.read_text());archive=root/record['archive']
        assert hashlib.sha256(archive.read_bytes()).hexdigest()==record['sha256']
        with tarfile.open(archive,'r:xz') as bundle:
            assert set(bundle.getnames())==set(record['members'])
            for path,item in record['members'].items():
                data=bundle.extractfile(path).read()
                assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256'],path
                result[path]=data
    return result

@lru_cache(maxsize=2)
def aliased(root_text):
    root=Path(root_text);result={}
    for name in ('exact-evidence-aliases.json','source-evidence-aliases.json'):
        receipt=root/'artifacts/android-phase-5/preservation'/name
        if not receipt.is_file():continue
        for record in json.loads(receipt.read_text())['archives']:
            base=root.parent if record['base']=='workspace' else root
            archive=base/record['archive']
            assert archive.resolve().is_relative_to(root.parent.resolve())
            assert hashlib.sha256(archive.read_bytes()).hexdigest()==record['sha256']
            with tarfile.open(archive,'r:xz') as bundle:
                for path,item in record['members'].items():
                    data=bundle.extractfile(item['member']).read()
                    assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256'],path
                    result[path]=data
    return result

def known_evidence_paths(root):
    names=set()
    for receipt in (root/'artifacts/android-phase-5/preservation').glob('*.json'):
        record=json.loads(receipt.read_text())
        if 'members' in record:names.update(record['members'])
        for group in record.get('archives',[]):names.update(group['members'])
        names.update(record.get('file_aliases',{}))
    return names

def historical_bytes(root,name):
    path=root/name
    if path.is_file():return path.read_bytes()
    receipt=root/'artifacts/android-phase-5/preservation/exact-evidence-aliases.json'
    record=json.loads(receipt.read_text()) if receipt.is_file() else {}
    item=record.get('file_aliases',{}).get(name)
    if item:
        assert item['target']!=name
        data=historical_bytes(root,item['target'])
        assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256']
        return data
    value=relocated(str(root)).get(name)
    if value is None:value=aliased(str(root)).get(name)
    return value if value is not None else phase4_bytes(root,name)

def verify_historical(root,frozen):
    moved=0
    for name,digest in frozen.items():
        assert hashlib.sha256(historical_bytes(root,name)).hexdigest()==digest,('Historical bytes changed',name)
        moved+=not (root/name).is_file()
    return {'files':len(frozen),'relocated_members':moved,'byte_identical':True}

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description='Read/recover one exact preserved evidence file without overwriting any existing path.')
    p.add_argument('path',help='Original repository-relative evidence path')
    p.add_argument('--output',help='Optional new output path; otherwise print original SHA256/size')
    a=p.parse_args();root=Path(__file__).resolve().parents[1]
    assert not Path(a.path).is_absolute() and '..' not in Path(a.path).parts
    data=historical_bytes(root,a.path)
    if a.output:
        dest=Path(a.output).resolve();assert dest.is_relative_to(root.parent) and not dest.exists()
        dest.parent.mkdir(parents=True,exist_ok=True)
        with dest.open('xb') as f:f.write(data)
    print(json.dumps({'path':a.path,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'output':a.output}))
