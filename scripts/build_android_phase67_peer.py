#!/usr/bin/env python3
"""Build a source-fingerprinted disposable Linux interop peer. Never runs historical desktop tests."""
import argparse, datetime as dt, hashlib, json, os, re, subprocess, time
from pathlib import Path
from android_phase67_inputs import desktop_inputs, sha
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--record',required=True);a=p.parse_args();assert re.fullmatch('[a-z0-9_-]+',a.record)
record=ROOT/'artifacts/android-phase-6-7/peer-builds'/a.record;record.mkdir(parents=True,exist_ok=False)
target=Path(os.environ.get('NARADA_AGENT_TARGET',str(Path.home()/'.cache/narada-rust-phase2/agent-target')))
env=os.environ.copy();env.update(CARGO_TARGET_DIR=str(target),CARGO_BUILD_JOBS='1',CARGO_INCREMENTAL='0',CARGO_PROFILE_DEV_DEBUG='0')
args=['cargo','build','--locked','-p','narada-agent','--features','narada-connection/dev-vault','--jobs','1']
before=desktop_inputs(ROOT);started=dt.datetime.now(dt.timezone.utc).isoformat();begin=time.monotonic()
with (record/'cargo.log').open('w') as log:r=subprocess.run(args,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
after=desktop_inputs(ROOT);assert before==after,'Peer sources changed during compilation'
binary=target/'debug/narada-agent'
report={'started_at_utc':started,'duration_seconds':round(time.monotonic()-begin,3),'command':args,'exit_code':r.returncode,'host':'Linux x86_64','vault':'disposable test-only dev-vault (NOT Windows DPAPI or production identity storage)','binary':str(binary),'binary_sha256':sha(binary) if r.returncode==0 else None,'binary_bytes':binary.stat().st_size if r.returncode==0 else None,'desktop_source_count':len(before),'desktop_source_sha256_before_and_after':before,'script_sha256':sha(Path(__file__)),'input_helper_sha256':sha(ROOT/'scripts/android_phase67_inputs.py'),'rustc':subprocess.check_output(['rustc','--version'],text=True).strip(),'historical_desktop_validation_run':False,'windows_execution':False,'independently_reviewed':False}
(record/'receipt.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='desktop_source_sha256_before_and_after'},indent=2));raise SystemExit(r.returncode)
