#!/usr/bin/env python3
"""One consolidated validation pass. Never reruns failed stages.
Requires installed Rust/Node dependencies and Chromium. Native execution is separate.
"""
from pathlib import Path
import hashlib, json, os, re, signal, subprocess, sys, time
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts/desktop-5-9'
OUT.mkdir(parents=True,exist_ok=True)
MARK=OUT/'pass-started.json'
REPORT=OUT/'validation-summary.json'
if MARK.exists() or REPORT.exists():
    raise SystemExit('This single pass already started. Preserve its evidence; do not silently rerun tests.')
ENV={**os.environ,'CARGO_INCREMENTAL':'0','NARADA_SLOW_TESTS':'1','NARADA_DEV_VAULT_KEY':'6b'*32}
tools=Path.home()/'.cache/narada-tools'
if (tools/'cargo/bin/cargo').exists():
    ENV.update(PATH=str(tools/'cargo/bin')+':'+ENV['PATH'],RUSTUP_HOME=str(tools/'rustup'),CARGO_HOME=str(tools/'cargo'),RUSTUP_TOOLCHAIN='stable')
if (Path.home()/'.cache/ms-playwright').exists():ENV['PLAYWRIGHT_BROWSERS_PATH']=str(Path.home()/'.cache/ms-playwright')
MARK.write_text(json.dumps({'project':'NARADA','version':'0.7.0','date':'2026-10-30','policy':'One pass after all source/test code; no failed-suite reruns','started_unix':time.time()},indent=2)+'\n')
# Freeze the source actually exercised; documentation/result updates are separate.
from source_hashes import capture
source_hashes=capture(ROOT)
(OUT/'tested-source-hashes.json').write_text(json.dumps(source_hashes,indent=2)+'\n')
historical={}
for p in sorted((ROOT/'artifacts/phase-4').rglob('*')):
    if p.is_file():historical[p.relative_to(ROOT).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
for name in ['PHASE-4-HANDOFF.md','SOURCE-MANIFEST.json']:
    if (ROOT/name).exists():historical[name]=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
(OUT/'historical-evidence-hashes.json').write_text(json.dumps(historical,indent=2)+'\n')
results=[]
def save():
    report={'project':'NARADA','version':'0.7.0','date':'2026-10-30','validation_policy':'single consolidated pass; zero retries','status':'in_progress','checks':results,'qualification':'Development evidence only. Windows runtime, physical LAN, malicious namespace racing, hardware power-loss, installer and independent security review are not qualified. Android is deferred.'}
    REPORT.write_text(json.dumps(report,indent=2)+'\n');return report

def run(name,command,timeout=900,gate=True):
    if not gate:
        results.append({'name':name,'status':'not_run','reason':'Prerequisite failed; no stale historical binary or broken build was tested.'});save();print(f'SKIP {name}',flush=True);return False
    print(f'RUN  {name}',flush=True);start=time.monotonic();out=OUT/(name+'.txt')
    proc=subprocess.Popen(command,cwd=ROOT,env=ENV,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,start_new_session=True)
    expired=False
    try:stdout,_=proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        expired=True;os.killpg(proc.pid,signal.SIGTERM)
        try:stdout,_=proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL);stdout,_=proc.communicate()
    out.write_text(stdout);entry={'name':name,'command':command,'status':'passed' if proc.returncode==0 and not expired else 'failed','exit_code':proc.returncode,'seconds':round(time.monotonic()-start,3),'log':str(out.relative_to(ROOT))}
    if expired:entry['timeout']=True
    if name=='rust-tests':
        matches=re.findall(r'test result: (?:ok|FAILED)\. (\d+) passed; (\d+) failed; (\d+) ignored',stdout);entry['passed_tests']=sum(int(x[0]) for x in matches);entry['failed_tests']=sum(int(x[1]) for x in matches);entry['skipped_tests']=sum(int(x[2]) for x in matches)
    if name=='javascript-tests':
        for key,label in [('passed_tests','pass'),('failed_tests','fail'),('skipped_tests','skipped')]:
            match=re.search(r'^# '+label+r' (\d+)',stdout,re.M)
            if match:entry[key]=int(match[1])
    if name in ['lifecycle','peer-security','file-security','transfer-security','content-offline','release-tool-tests']:
        match=re.search(r'Ran (\d+) tests?',stdout)
        if match:
            total=int(match[1]);fail=re.findall(r'(?:failures|errors)=(\d+)',stdout);skips=re.findall(r'skipped=(\d+)',stdout);entry.update(total_tests=total,failed_tests=sum(map(int,fail)),skipped_tests=sum(map(int,skips)),passed_tests=total-sum(map(int,fail))-sum(map(int,skips)))
    if name=='browser-tests' and (OUT/'browser-results.json').exists():
        stats=json.loads((OUT/'browser-results.json').read_text()).get('stats',{});entry.update(passed_tests=stats.get('expected',0),failed_tests=stats.get('unexpected',0),skipped_tests=stats.get('skipped',0),flaky_tests=stats.get('flaky',0))
    results.append(entry);save();print(f"{entry['status'].upper():5} {name} ({entry['seconds']}s)",flush=True)
    if entry['status']!='passed':print(stdout[-3500:],flush=True)
    return entry['status']=='passed'

run('rust-format',['cargo','fmt','--all','--check'])
run('ui-format',['npm','run','format:check'])
built=run('development-agent-build',['cargo','build','--locked','-p','narada-agent','--features','dev-preview'])
run('rust-tests',['cargo','test','--locked','--workspace','--exclude','narada-desktop','--features','narada-agent/dev-preview'],gate=built)
run('linux-clippy',['cargo','clippy','--locked','--workspace','--exclude','narada-desktop','--all-targets','--features','narada-agent/dev-preview','--','-D','warnings'],gate=built)
run('javascript-tests',['npm','test'])
run('native-command-parity',['npm','run','check:commands'])
uibuilt=run('ui-production-build',['npm','run','build'])
run('lifecycle',['python3','tests/process/agent_lifecycle.py'],gate=built)
run('peer-security',['python3','tests/process/pairing_security.py'],timeout=420,gate=built)
run('file-security',['python3','tests/process/files_security.py'],timeout=180,gate=built)
run('transfer-security',['python3','tests/process/transfers_security.py'],timeout=420,gate=built)
run('content-offline',['python3','tests/process/content_offline.py'],timeout=480,gate=built)
run('release-tool-tests',['python3','tests/tooling/release_tools.py'],timeout=90)
run('browser-tests',['npm','run','test:e2e:desktop'],timeout=900,gate=built and uibuilt)
run('host-resource-observation',['python3','scripts/measure_desktop.py'],timeout=60,gate=built)
run('lockfile-license-inventory',['python3','scripts/export_inventory.py'],timeout=180)
run('missing-release-evidence-is-blocked',['python3','scripts/release/check_release.py','--expect-blocked'],timeout=30)
run('windows-gnu-clippy',['cargo','clippy','--locked','-p','narada-agent','-p','narada-files','--target','x86_64-pc-windows-gnu','--all-targets','--','-D','warnings'],gate=built)
run('windows-gnu-link',['cargo','build','--locked','-p','narada-agent','--target','x86_64-pc-windows-gnu'],gate=built)
run('npm-audit',['npm','audit','--json'],timeout=90)
default=run('default-agent-build',['cargo','build','--locked','-p','narada-agent','--no-default-features'],gate=built)
run('default-feature-boundary',['python3','scripts/check_default_boundary.py'],timeout=40,gate=default)
run('restore-development-agent',['cargo','build','--locked','-p','narada-agent','--features','dev-preview'],gate=default)
unchanged=all((ROOT/name).is_file() and hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest for name,digest in historical.items())
results.append({'name':'historical-phase4-evidence-preserved','status':'passed' if unchanged else 'failed','files_checked':len(historical),'note':'Integrity preservation only; no historical suite was rerun or relabeled.'})
source_unchanged=capture(ROOT)==source_hashes
results.append({'name':'frozen-source-unchanged','status':'passed' if source_unchanged else 'failed'})
report=save();report['status']='passed' if all(r['status']=='passed' for r in results) else 'has_failures';report['passed_tests']=sum(r.get('passed_tests',0) for r in results);report['failed_tests']=sum(r.get('failed_tests',0) for r in results);report['skipped_tests']=sum(r.get('skipped_tests',0) for r in results);report['finished_unix']=time.time();report['rerun_count']=0
REPORT.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:report[k] for k in ['status','passed_tests','failed_tests','skipped_tests','rerun_count']},indent=2),flush=True)
sys.exit(0 if report['status']=='passed' else 1)
