#!/usr/bin/env python3
"""Verify an explicitly supplied OFFLINE update. Never downloads or executes it.
A trusted-key digest must come from the owner/release channel, NOT the manifest.
"""
import argparse, hashlib, json, re, subprocess, tempfile
from pathlib import Path
MAX_MANIFEST=16384
def version(text):
    if not isinstance(text,str) or not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)',text):raise ValueError('Only canonical stable desktop versions are accepted')
    return tuple(map(int,text.split('.')))
def verify(manifest,signature,key,artifact,trusted,installed):
    if manifest.stat().st_size>MAX_MANIFEST or key.stat().st_size>16384 or signature.stat().st_size!=64:raise ValueError('Manifest, key or signature size is invalid')
    raw=manifest.read_bytes();public=key.read_bytes();sig=signature.read_bytes()
    if len(raw)>MAX_MANIFEST or len(public)>16384 or len(sig)!=64:raise ValueError('Input changed beyond its size bound')
    if not re.fullmatch('[0-9a-f]{64}',trusted) or hashlib.sha256(public).hexdigest()!=trusted:raise ValueError('The signing key is not the independently pinned key')
    # Verify precisely the bytes parsed below, not a second read of mutable caller paths.
    with tempfile.TemporaryDirectory(prefix='narada-update-verify-') as directory:
        frozen=Path(directory);m=frozen/'manifest.json';k=frozen/'public.pem';s=frozen/'signature'
        m.write_bytes(raw);k.write_bytes(public);s.write_bytes(sig)
        info=subprocess.run(['openssl','pkey','-pubin','-in',str(k),'-text_pub','-noout'],capture_output=True,text=True,timeout=15)
        if info.returncode or 'ED25519' not in info.stdout:raise ValueError('Only the approved Ed25519 manifest signing algorithm is supported')
        result=subprocess.run(['openssl','pkeyutl','-verify','-pubin','-inkey',str(k),'-rawin','-in',str(m),'-sigfile',str(s)],capture_output=True,timeout=15)
        if result.returncode:raise ValueError('Offline update signature verification failed')
    def unique(pairs):
        value={}
        for k,v in pairs:
            if k in value:raise ValueError('Duplicate manifest fields are refused')
            value[k]=v
        return value
    data=json.loads(raw,object_pairs_hook=unique)
    if set(data)!={'application','version','platform','artifact','size','sha256','metadata_schema','channel'} or data['application']!='NARADA' or data['platform']!='windows-x86_64' or data['channel']!='qualified-desktop':raise ValueError('Unknown or wrong update scope')
    if version(data['version'])<=version(installed):raise ValueError('Downgrade/replay is refused; migrations are not silently rolled back')
    if data['artifact']!=artifact.name or data['size']!=str(artifact.stat().st_size) or data['metadata_schema']!=6:raise ValueError('Artifact size, filename or schema is incompatible')
    digest=hashlib.sha256()
    with artifact.open('rb') as handle:
        for chunk in iter(lambda:handle.read(1024*1024),b''):digest.update(chunk)
    if digest.hexdigest()!=data['sha256']:raise ValueError('Artifact digest mismatch')
    return {'verified':True,'executed':False,'version':data['version'],'next':'Independently verify the Windows Authenticode publisher and review the backup/migration checklist before manual install.'}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--signature',type=Path,required=True);p.add_argument('--public-key',type=Path,required=True);p.add_argument('--artifact',type=Path,required=True);p.add_argument('--trusted-key-sha256',required=True);p.add_argument('--installed-version',required=True);a=p.parse_args()
    try:print(json.dumps(verify(a.manifest,a.signature,a.public_key,a.artifact,a.trusted_key_sha256,a.installed_version),indent=2))
    except (ValueError,OSError,KeyError,TypeError,subprocess.TimeoutExpired) as error:raise SystemExit('REFUSED: '+str(error))
