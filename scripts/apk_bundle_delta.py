#!/usr/bin/env python3
"""Byte-preserving development APK-container deltas; no signing/rebuilding APKs.
Restore/verify uses Python's standard library only. Creation uses bsdiff4==1.2.6.
The delta is over exact uncompressed tar bytes, not the signed APKs' contents.
XZ is an outer delivery wrapper; preset-6 byte identity is checked, never assumed.
"""
import argparse, bz2, hashlib, json, lzma, platform, sys
from pathlib import Path
if not __debug__:
    raise SystemExit("Integrity checks require normal Python execution; do not use -O/PYTHONOPTIMIZE.")
MAX=256*1024*1024

def digest(b): return hashlib.sha256(b).hexdigest()
def inflate(b, expected):
    assert 0<=expected<=MAX
    d=lzma.LZMADecompressor(memlimit=256*1024*1024);out=d.decompress(b,max_length=expected+1)
    assert len(out)==expected and d.eof and not d.unused_data,'XZ size/framing mismatch'
    return out

def integer(b):
    assert len(b)==8
    n=int.from_bytes(b,'little') & ((1<<63)-1)
    return -n if b[7]&128 else n

def bzip(b, limit):
    d=bz2.BZ2Decompressor();out=d.decompress(b,max_length=limit+1)
    assert len(out)<=limit and d.eof and not d.unused_data,'Invalid bounded delta block'
    return out

def patch(old, delta, expected):
    assert delta[:8]==b'BSDIFF40' and len(delta)>=32
    controls,diffs,size=(integer(delta[i:i+8]) for i in (8,16,24))
    assert controls>=0 and diffs>=0 and size==expected and size<=MAX and 32+controls+diffs<=len(delta)
    ctrl=bzip(delta[32:32+controls],64*1024*1024)
    diff=bzip(delta[32+controls:32+controls+diffs],size)
    extra=bzip(delta[32+controls+diffs:],size)
    assert len(ctrl)%24==0
    output=bytearray(size);pos=oldpos=cp=dp=ep=0
    while pos<size:
        assert cp+24<=len(ctrl),'Truncated delta controls'
        x,y,z=(integer(ctrl[cp+i:cp+i+8]) for i in (0,8,16));cp+=24
        assert x>=0 and y>=0 and pos+x+y<=size and dp+x<=len(diff) and ep+y<=len(extra)
        segment=diff[dp:dp+x]
        if 0<=oldpos and oldpos+x<=len(old) and not any(segment):output[pos:pos+x]=old[oldpos:oldpos+x]
        else:
            for i,v in enumerate(segment):output[pos+i]=(v+(old[oldpos+i] if 0<=oldpos+i<len(old) else 0))&255
        pos+=x;oldpos+=x;dp+=x
        output[pos:pos+y]=extra[ep:ep+y];pos+=y;ep+=y;oldpos+=z
    assert cp==len(ctrl) and dp==len(diff) and ep==len(extra),'Unused delta data'
    return bytes(output)

def restore_bytes(metadata, base_path, delta_path, uncompressed=False):
    m=json.loads(Path(metadata).read_text());assert m['schema']==1 and m['format']=='bsdiff40-exact-tar-xz6'
    base=Path(base_path).read_bytes();delta=Path(delta_path).read_bytes()
    assert len(base)==m['base']['bytes'] and digest(base)==m['base']['sha256'],'Wrong base archive'
    assert len(delta)==m['delta']['bytes'] and digest(delta)==m['delta']['sha256'],'Wrong/corrupt delta'
    raw=inflate(base,m['base']['tar_bytes']);assert digest(raw)==m['base']['tar_sha256']
    value=patch(raw,delta,m['target']['tar_bytes']);assert digest(value)==m['target']['tar_sha256'],'Recovered tar differs'
    if uncompressed:return value,m
    packed=lzma.compress(value,preset=6)
    assert len(packed)==m['target']['bytes'] and digest(packed)==m['target']['sha256'],'This platform encoder did not reproduce the outer XZ wrapper. Use --uncompressed-tar; the exact signed APK tar bytes were already verified.'
    return packed,m

def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    c=sub.add_parser('create');c.add_argument('--base',required=True);c.add_argument('--target',required=True);c.add_argument('--prefix',required=True)
    r=sub.add_parser('restore');r.add_argument('--metadata',required=True);r.add_argument('--base',required=True);r.add_argument('--delta');r.add_argument('--output');r.add_argument('--verify-only',action='store_true');r.add_argument('--uncompressed-tar',action='store_true')
    a=p.parse_args()
    if a.command=='create':
        import bsdiff4, importlib.metadata
        assert importlib.metadata.version('bsdiff4')=='1.2.6'
        b=Path(a.base);t=Path(a.target);prefix=Path(a.prefix);meta=prefix.with_suffix('.json');dpath=prefix.with_suffix('.bsdiff')
        assert not meta.exists() and not dpath.exists()
        old=b.read_bytes();new=t.read_bytes();raw_old=lzma.decompress(old,memlimit=MAX);raw_new=lzma.decompress(new,memlimit=MAX)
        assert len(raw_old)<=MAX and len(raw_new)<=MAX and lzma.compress(raw_new,preset=6)==new
        delta=bsdiff4.diff(raw_old,raw_new);assert patch(raw_old,delta,len(raw_new))==raw_new
        m={'schema':1,'format':'bsdiff40-exact-tar-xz6','base':{'filename':b.name,'sha256':digest(old),'bytes':len(old),'tar_sha256':digest(raw_old),'tar_bytes':len(raw_old)},'target':{'filename':t.name,'sha256':digest(new),'bytes':len(new),'tar_sha256':digest(raw_new),'tar_bytes':len(raw_new)},'delta':{'filename':dpath.name,'sha256':digest(delta),'bytes':len(delta)},'generator':'bsdiff4 1.2.6; development packaging only','validated_restorer':'Python '+platform.python_version()+' standard library, Linux x86_64','original_signed_apk_bytes_unchanged':True,'byte_exact_tar_and_xz_roundtrip_verified':True,'windows_macos_restore_executed':False,'release_approval':False}
        prefix.parent.mkdir(parents=True,exist_ok=True);dpath.write_bytes(delta);meta.write_text(json.dumps(m,indent=2)+'\n')
        value,_=restore_bytes(meta,b,dpath);assert value==new
        print(json.dumps(m,indent=2));return
    meta=Path(a.metadata);m=json.loads(meta.read_text())
    name=m['delta']['filename'];assert Path(name).name==name
    delta=Path(a.delta) if a.delta else meta.parent/name
    value,m=restore_bytes(meta,a.base,delta,a.uncompressed_tar)
    target=m['target']['filename'];assert Path(target).name==target
    if a.uncompressed_tar:target=target[:-3] if target.endswith('.xz') else target+'.tar'
    output=Path(a.output) if a.output else meta.parent/target
    if not a.verify_only:
        output.parent.mkdir(parents=True,exist_ok=True)
        with output.open('xb') as f:f.write(value)
    print(json.dumps({'verified':True,'output':None if a.verify_only else str(output),'bytes':len(value),'sha256':digest(value),'signed_apk_bytes_unchanged':True}))
if __name__=='__main__':main()
