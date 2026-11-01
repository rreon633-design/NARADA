"""Read exact frozen historical bytes, including explicitly relocated log/HTML evidence.
No tests, recovery/rekeying, extraction or mutation is performed by this helper.
"""
from functools import lru_cache
import hashlib
import json
import tarfile
from pathlib import Path

@lru_cache(maxsize=4)
def _relocated(root_text):
    root=Path(root_text)
    receipt_path=root/'artifacts/android-phase-4/preparation/historical-log-relocation.json'
    if not receipt_path.exists():return {}
    receipt=json.loads(receipt_path.read_text());archive=root.parent/receipt['archive']
    if not archive.is_file():
        raise FileNotFoundError('Full historical audit needs the separately preserved historical log container: '+str(archive))
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==receipt['sha256']
    result={}
    with tarfile.open(archive,'r:xz') as bundle:
        for name,item in receipt['members'].items():
            data=bundle.extractfile(name).read()
            assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256'],name
            result[name]=data
    return result

def historical_bytes(root, name):
    path=root/name
    if path.is_file():return path.read_bytes()
    data=_relocated(str(root)).get(name)
    if data is None:raise FileNotFoundError('Historical evidence missing: '+name)
    return data

def verify_historical(root, frozen):
    relocated=0
    for name,digest in frozen.items():
        data=historical_bytes(root,name)
        assert hashlib.sha256(data).hexdigest()==digest,('Historical input changed',name)
        relocated+=not (root/name).is_file()
    return {'files':len(frozen),'relocated_log_or_html_members':relocated,'byte_identical':True}
