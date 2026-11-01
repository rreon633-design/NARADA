"""Resolve exact preserved pre-Phase6/7 bytes, including explicitly superseded desktop source.
The upload extension is user-authorized; original source/campaigns are not relabeled.
"""
from functools import lru_cache
from pathlib import Path
import hashlib,io,json,tarfile,zipfile
from android_phase5_history import historical_bytes as phase5_bytes,known_evidence_paths as phase5_names

@lru_cache(maxsize=2)
def saved(root_text):
    root=Path(root_text);base=root/'artifacts/android-phase-6-7/preservation';data={}
    receipt=base/'desktop-before-extension.json'
    if receipt.is_file():
        r=json.loads(receipt.read_text());archive=root.parent/r['archive']
        assert hashlib.sha256(archive.read_bytes()).hexdigest()==r['archive_sha256']
        with tarfile.open(archive,'r:xz') as b:
            for name,i in r['members'].items():
                value=b.extractfile(i['member']).read()
                assert len(value)==i['bytes'] and hashlib.sha256(value).hexdigest()==i['sha256']
                data[name]=value
    receipt=base/'legacy-evidence-aliases.json'
    if receipt.is_file():
        r=json.loads(receipt.read_text());archive=root.parent/r['archive']
        assert hashlib.sha256(archive.read_bytes()).hexdigest()==r['archive_sha256']
        with tarfile.open(archive,'r:xz') as b:
            for name in sorted({i['zip'] for i in r['members'].values()}):
                with zipfile.ZipFile(io.BytesIO(b.extractfile(name).read())) as z:
                    for dest,i in r['members'].items():
                        if i['zip']!=name:continue
                        value=z.read(i['member'])
                        assert len(value)==i['bytes'] and hashlib.sha256(value).hexdigest()==i['sha256']
                        data[dest]=value
    for receipt in sorted(base.glob('*-relocation.json')):
        r=json.loads(receipt.read_text());archive=root/r['archive']
        assert hashlib.sha256(archive.read_bytes()).hexdigest()==r['sha256']
        with tarfile.open(archive,'r:xz') as b:
            for name,i in r['members'].items():
                value=b.extractfile(i.get('member',name)).read()
                assert len(value)==i['bytes'] and hashlib.sha256(value).hexdigest()==i['sha256']
                data[name]=value
    for receipt in sorted(base.glob('content-aliases-*.json')):
        for name,i in json.loads(receipt.read_text())['members'].items():
            source=i['source'];value=data.get(source)
            if value is None:value=phase5_bytes(root,source)
            assert len(value)==i['bytes'] and hashlib.sha256(value).hexdigest()==i['sha256']
            data[name]=value
    return data

def historical_bytes(root,name):
    # An original desktop file can legitimately differ from the explicitly extended live version.
    original=saved(str(root)).get(name)
    return original if original is not None else phase5_bytes(root,name)

def verify_historical(root,frozen):
    changed=[];moved=0
    for name,digest in frozen.items():
        assert hashlib.sha256(historical_bytes(root,name)).hexdigest()==digest,('Lost/changed historical bytes',name)
        path=root/name
        if not path.is_file():moved+=1
        elif hashlib.sha256(path.read_bytes()).hexdigest()!=digest:changed.append(name)
    return {'original_inputs':len(frozen),'all_original_bytes_preserved':True,'relocated_members':moved,'explicitly_superseded_live_paths':changed}

def known_evidence_paths(root):return phase5_names(root)|set(saved(str(root)))

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('path');p.add_argument('--output');a=p.parse_args()
    root=Path(__file__).resolve().parents[1];assert not Path(a.path).is_absolute() and '..' not in Path(a.path).parts
    data=historical_bytes(root,a.path)
    if a.output:
        dest=Path(a.output).resolve();assert dest.is_relative_to(root.parent) and not dest.exists()
        dest.parent.mkdir(parents=True,exist_ok=True)
        with dest.open('xb') as f:f.write(data)
    print(json.dumps({'path':a.path,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'output':a.output}))
