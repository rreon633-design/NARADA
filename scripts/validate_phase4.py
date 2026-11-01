#!/usr/bin/env python3
"""One consolidated validation pass. Never reruns failed stages.
Requires installed Rust/Node dependencies and Chromium. Native execution is separate.
"""
from pathlib import Path
import hashlib, json, os, re, signal, subprocess, sys, time
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts/phase-4'
OUT.mkdir(parents=True,exist_ok=True)
MARK=OUT/'pass-started.json'
REPORT=OUT/'validation-summary.json'
if MARK.exists() or REPORT.exists():
    raise SystemExit('This single pass already started. Preserve its evidence; do not silently rerun tests.')
ENV={**os.environ,'CARGO_INCREMENTAL':'0','NARADA_SLOW_TESTS':'1'}
tools=Path.home()/'.cache/narada-tools'
if (tools/'cargo/bin/cargo').exists():
    ENV.update(PATH=str(tools/'cargo/bin')+':'+ENV['PATH'],RUSTUP_HOME=str(tools/'rustup'),CARGO_HOME=str(tools/'cargo'),RUSTUP_TOOLCHAIN='stable')
if (Path.home()/'.cache/ms-playwright').exists():ENV['PLAYWRIGHT_BROWSERS_PATH']=str(Path.home()/'.cache/ms-playwright')
MARK.write_text(json.dumps({'project':'NARADA','version':'0.4.0','date':'2026-10-30','policy':'One pass after all source/test code; no failed-suite reruns','started_unix':time.time()},indent=2)+'\n')
# Freeze the source actually exercised; documentation/result updates are separate.
source_hashes={}
for prefix in ['crates','apps','desktop-ui','scripts','tests','.github']:
    for parent,dirs,files in os.walk(ROOT/prefix):
        dirs[:]=[d for d in dirs if d not in {'node_modules','target','dist','gen','binaries','__pycache__','build','.cache'}]
        for name in files:
            p=Path(parent)/name
            if p.suffix in {'.rs','.js','.mjs','.css','.sql','.toml','.json','.py','.yml'}:source_hashes[p.relative_to(ROOT).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
for name in ['Cargo.toml','Cargo.lock','package.json','package-lock.json','playwright.config.js','rust-toolchain.toml']:
    source_hashes[name]=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
(OUT/'tested-source-hashes.json').write_text(json.dumps(source_hashes,indent=2)+'\n')
results=[]
def save():
    report={'project':'NARADA','version':'0.4.0','date':'2026-10-30','validation_policy':'single consolidated pass; zero retries','status':'in_progress','checks':results,'qualification':'Development evidence only. Windows runtime, physical LAN, malicious namespace racing, hardware power-loss, installer and independent security review are not qualified. Android is deferred.'}
    REPORT.write_text(json.dumps(report,indent=2)+'\n');return report

def run(name,command,timeout=600,gate=True):
    if not gate:
        results.append({'name':name,'status':'not_run','reason':'Prerequisite failed; no stale Phase 3 binary or broken build was tested.'});save();print(f'SKIP {name}',flush=True);return False
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
    if name in ['lifecycle','peer-security','file-security','transfer-security']:
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
run('browser-tests',['npm','run','test:e2e'],timeout=600,gate=built and uibuilt)
run('windows-gnu-clippy',['cargo','clippy','--locked','-p','narada-agent','-p','narada-files','--target','x86_64-pc-windows-gnu','--all-targets','--','-D','warnings'],gate=built)
run('windows-gnu-link',['cargo','build','--locked','-p','narada-agent','--target','x86_64-pc-windows-gnu'],gate=built)
run('npm-audit',['npm','audit','--json'],timeout=90)
default=run('default-agent-build',['cargo','build','--locked','-p','narada-agent','--no-default-features'],gate=built)
run('default-feature-boundary',['python3','scripts/check_default_boundary.py'],timeout=40,gate=default)
run('restore-development-agent',['cargo','build','--locked','-p','narada-agent','--features','dev-preview'],gate=default)
report=save();report['status']='passed' if all(r['status']=='passed' for r in results) else 'has_failures';report['passed_tests']=sum(r.get('passed_tests',0) for r in results);report['failed_tests']=sum(r.get('failed_tests',0) for r in results);report['skipped_tests']=sum(r.get('skipped_tests',0) for r in results);report['finished_unix']=time.time();report['rerun_count']=0
REPORT.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:report[k] for k in ['status','passed_tests','failed_tests','skipped_tests','rerun_count']},indent=2),flush=True)
sys.exit(0 if report['status']=='passed' else 1)
