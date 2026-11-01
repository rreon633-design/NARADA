#!/usr/bin/env python3
"""Pinned official native toolchain restoration. No device/profile/signing keys."""
import argparse,re,hashlib,json,os,stat,subprocess,tarfile,tomllib,urllib.request,zipfile
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--record',required=True);args=parser.parse_args();assert re.fullmatch(r'[a-z0-9-]+',args.record)
R=Path(__file__).resolve().parents[1];T=Path.home()/'.cache/narada-android-tools';C=Path.home()/'.cache/narada-rust-phase2';P=R/'artifacts/android-phase-6-7/preparation'
T.mkdir(parents=True,exist_ok=True);receipts=[]
def fetch(url,path,kind=None,want=None):
 if not path.exists():subprocess.run(['curl','-fL','--retry','2','--connect-timeout','30',url,'-o',str(path)],check=True)
 with path.open('rb') as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
 if want:
  with path.open('rb') as f:assert hashlib.file_digest(f,kind).hexdigest()==want,path
 receipts.append({'url':url,'file':path.name,'bytes':path.stat().st_size,'sha256':sha,'published_digest_kind':kind,'published_digest':want})
 return path
ndk=fetch('https://dl.google.com/android/repository/android-ndk-r30-linux.zip',T/'android-ndk-r30-linux.zip','sha1','5107f898313790e449e87eee2183d9a20602dee9')
assert ndk.stat().st_size==738633529
if not (T/'android-ndk-r30/source.properties').exists():
 with zipfile.ZipFile(ndk) as z:
  assert all(not Path(i.filename).is_absolute() and ".." not in Path(i.filename).parts and i.filename.startswith("android-ndk-r30/") for i in z.infolist())
  z.extractall(T)
  for info in z.infolist():
   mode=info.external_attr>>16
   if stat.S_ISLNK(mode):
    path=T/info.filename;link=z.read(info).decode()
    assert not link.startswith('/') and (path.parent/link).resolve().is_relative_to(T)
    path.unlink();path.symlink_to(link)
   elif mode and not info.is_dir():(T/info.filename).chmod(mode&0o777)
ndk_link=T/'sdk/ndk/30.0.16248370'
ndk_link.parent.mkdir(parents=True,exist_ok=True)
if not ndk_link.exists():ndk_link.symlink_to(T/'android-ndk-r30',target_is_directory=True)
assert ndk_link.resolve()==(T/'android-ndk-r30').resolve()
manifest=fetch('https://static.rust-lang.org/dist/2026-10-15/channel-rust-1.99.1.toml',C/'channel-rust-1.99.1.toml')
m=tomllib.loads(manifest.read_text())
for target in ['aarch64-linux-android','armv7-linux-androideabi','x86_64-linux-android','i686-linux-android']:
 item=m['pkg']['rust-std']['target'][target];assert item['available']
 source=fetch(item['xz_url'],C/('rust-std-'+target+'.tar.xz'),'sha256',item['xz_hash'])
 with tarfile.open(source) as a:
  name=a.getnames()[0].split('/')[0]
  if not (C/name/'install.sh').exists():a.extractall(C,filter='data')
 subprocess.run(['bash',str(C/name/'install.sh'),'--prefix='+str(C/'toolchain'),'--disable-ldconfig'],check=True)
(P/(args.record+'.json')).write_text(json.dumps({'ndk':'30.0.16248370','ndk_reference':'https://developer.android.com/ndk/downloads','rust':'1.99.1','downloads':receipts,'native_device_execution':False,'note':'NDK checked against official published SHA1/size; SHA256 recorded. Rust Android std checked against official HTTPS manifest. Not independent publisher/security review.'},indent=2)+'\n')
print('Pinned NDK and four Android Rust standard libraries installed')
