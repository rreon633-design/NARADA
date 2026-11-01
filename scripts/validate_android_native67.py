#!/usr/bin/env python3
"""New Linux Rust-adapter validation record. NOT Android/JNI/device execution or review."""
import argparse, datetime as dt, hashlib, json, os, re, subprocess, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--record',required=True);a=p.parse_args();assert re.fullmatch(r'[a-z0-9_-]+',a.record)
record=ROOT/'artifacts/android-phase-6-7/native-runs'/a.record;record.mkdir(parents=True,exist_ok=False)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
rows={};exit_code=0
for library,expected in [('age',12),('olm',20)]:
 base=ROOT/'apps/android'/('native-'+library)
 paths=[base/'Cargo.toml',base/'Cargo.lock',base/'src/lib.rs']
 before={p.relative_to(ROOT).as_posix():sha(p) for p in paths}
 env=os.environ.copy();env['CARGO_BUILD_JOBS']='1';env['CARGO_INCREMENTAL']='0';env['CARGO_TARGET_DIR']=str(Path.home()/('.cache/narada-android-native-'+library+'-target'))
 args=['cargo','test','--locked','--manifest-path',str(base/'Cargo.toml'),'--jobs','1']
 started=dt.datetime.now(dt.timezone.utc).isoformat();begin=time.monotonic()
 with (record/(library+'.log')).open('w') as log:r=subprocess.run(args,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
 after={p.relative_to(ROOT).as_posix():sha(p) for p in paths};assert before==after
 text=(record/(library+'.log')).read_text();counts=[tuple(map(int,m)) for m in re.findall(r'test result: (?:ok|FAILED)\. (\d+) passed; (\d+) failed; (\d+) ignored;',text)]
 assert counts,'No native test results found'
 passed,failed,ignored=map(sum,zip(*counts))
 success=r.returncode==0 and passed==expected and failed==0 and ignored==0
 rows[library]={'command':args,'started_at_utc':started,'duration_seconds':round(time.monotonic()-begin,3),'exit_code':r.returncode,'passed':passed,'failed':failed,'ignored':ignored,'source_sha256_before_and_after':before,'log_sha256':sha(record/(library+'.log')),'android_execution':False,'independent_review':False}
 if not success:exit_code=1
report={'host':'Linux x86_64','libraries':rows,'passed':sum(r['passed'] for r in rows.values()),'exit_code':exit_code,'script_sha256':sha(Path(__file__)),'android_execution':False,'independent_review':False}
(record/'summary.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));raise SystemExit(exit_code)
