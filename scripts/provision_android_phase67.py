#!/usr/bin/env python3
"""Restore excluded build caches from pinned prior receipts; no app data or keys restored."""
import argparse,re,hashlib,json,subprocess,tarfile,time,zipfile
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--record',required=True);args=parser.parse_args();assert re.fullmatch(r'[a-z0-9-]+',args.record)
R=Path(__file__).resolve().parents[1];C=Path.home()/'.cache';T=C/'narada-android-tools';S=T/'sdk';T.mkdir(parents=True,exist_ok=True)
receipts=[]
def fetch(url,path,kind,expected):
    if not path.exists():subprocess.run(['curl','-fL','--retry','3','--connect-timeout','30',url,'-o',str(path)],check=True)
    actual=hashlib.new(kind,path.read_bytes()).hexdigest();assert actual==expected,(path,actual)
    receipts.append({'url':url,'file':path.name,kind:actual,'bytes':path.stat().st_size})
p=json.loads((R/'artifacts/android-phase-1/preparation/tool-provisioning.json').read_text())
jdk=T/'jdk.tar.gz';fetch(p['jdk_url'],jdk,'sha256',p['jdk_sha256'])
with tarfile.open(jdk) as archive:
    name=archive.getnames()[0].split('/')[0]
    if not (T/name/'bin/java').exists():archive.extractall(T,filter='data')
(T/'java-home.txt').write_text(str(T/name)+'\n')
command=T/p['android_command_tools'];fetch('https://dl.google.com/android/repository/'+command.name,command,'sha1',p['command_tools_sha1'])
if not (S/'cmdline-tools/latest/bin/sdkmanager').exists():
    with zipfile.ZipFile(command) as archive:archive.extractall(T/'command-unpack')
    (S/'cmdline-tools').mkdir(parents=True,exist_ok=True)
    (T/'command-unpack/cmdline-tools').rename(S/'cmdline-tools/latest')
    for f in (S/'cmdline-tools/latest/bin').iterdir():f.chmod(0o755)
rust=C/'narada-rust-phase2';rust.mkdir(exist_ok=True)
for tool,item in json.loads((R/'artifacts/android-phase-2/preparation/rust-toolchain.json').read_text()).items():
    dest=rust/(tool+'.tar.xz');fetch(item['url'],dest,'sha256',item['sha256'])
    with tarfile.open(dest) as archive:
        dirname=archive.getnames()[0].split('/')[0]
        if not (rust/dirname/'install.sh').exists():archive.extractall(rust,filter='data')
    subprocess.run(['bash',str(rust/dirname/'install.sh'),'--prefix='+str(rust/'toolchain'),'--disable-ldconfig'],check=True)
(R/'artifacts/android-phase-6-7/preparation'/(args.record+'.json')).write_text(json.dumps({'downloads':receipts,'no_private_signing_key_restored':True,'note':'Prior excluded SDK/dependency/agent caches were absent after workspace restore. Source/history remained. A fresh debug signer must not be claimed as an in-place update to prior APKs.'},indent=2)+'\n')
