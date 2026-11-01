#!/usr/bin/env python3
"""Build the pinned stateless Olm JNI library; no downloads, Android execution or fallback.
Requires Rust 1.99.1, its four Android std targets and NDK 30.0.16248370.
Generated binaries and receipts live under apps/android/app/build (not source).
"""
import argparse,hashlib,json,os,shutil,subprocess,time
from pathlib import Path
R=Path(__file__).resolve().parents[1];P=R/'apps/android';M=P/'native-olm';O=P/'app/build/generated/olm';O.mkdir(parents=True,exist_ok=True)
a=argparse.ArgumentParser();a.add_argument('--host',action='store_true');a.add_argument('--android',action='store_true');args=a.parse_args();assert args.host or args.android
version=subprocess.check_output(['rustc','--version'],text=True).strip();assert version.startswith('rustc 1.99.1 '),version
env=os.environ.copy();env['CARGO_BUILD_JOBS']='1';env['CARGO_INCREMENTAL']='0'
target=Path(env.get('NARADA_OLM_TARGET_DIR',str(P/'app/build/native-olm-target')));env['CARGO_TARGET_DIR']=str(target)
sources={p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (M/'Cargo.toml',M/'Cargo.lock',M/'src/lib.rs')}
items=[];start=time.monotonic()
def build(triple=None,abi=None,linker=None):
 e=env.copy();cmd=['cargo','build','--locked','--release','--manifest-path',str(M/'Cargo.toml')]
 if triple:
  cmd+=['--target',triple];e['CARGO_TARGET_'+triple.replace('-','_').upper()+'_LINKER']=str(linker)
  e['CARGO_TARGET_'+triple.replace('-','_').upper()+'_RUSTFLAGS']='-C link-arg=-Wl,-z,max-page-size=16384'
 subprocess.run(cmd,env=e,check=True)
 src=target/((triple+'/') if triple else '')/'release/libnarada_olm.so'
 dest=O/('jniLibs/'+abi if abi else 'host')/'libnarada_olm.so';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dest)
 items.append({'abi':abi or 'linux-x86_64-host','target':triple or 'x86_64-unknown-linux-gnu','api':29 if triple else None,'path':dest.relative_to(R).as_posix(),'bytes':dest.stat().st_size,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
if args.host:build()
if args.android:
 ndk=Path(os.environ.get('ANDROID_NDK_HOME',str(Path.home()/'.cache/narada-android-tools/android-ndk-r30')))
 assert 'Pkg.Revision = 30.0.16248370' in (ndk/'source.properties').read_text()
 bin=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
 for triple,abi,clang in [('aarch64-linux-android','arm64-v8a','aarch64-linux-android29-clang'),('armv7-linux-androideabi','armeabi-v7a','armv7a-linux-androideabi29-clang'),('x86_64-linux-android','x86_64','x86_64-linux-android29-clang'),('i686-linux-android','x86','i686-linux-android29-clang')]:build(triple,abi,bin/clang)
receipt={'bridge_version':2,'vodozemac':'0.11.1','mode':'Olm v1; 64-bit MAC; experimental-session-config disabled','rust':version,'ndk':'30.0.16248370' if args.android else None,'source_sha256':sources,'libraries':items,'duration_seconds':round(time.monotonic()-start,3),'android_executed':False,'independently_reviewed':False}
(O/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
