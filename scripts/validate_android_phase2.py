#!/usr/bin/env python3
"""Run one Android Phase 2 validation stage into a NEW evidence directory. Never touches desktop evidence."""
import argparse, datetime as dt, hashlib, json, os, shutil, subprocess, time
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
PROJECT=ROOT/'apps/android'
parser=argparse.ArgumentParser()
parser.add_argument('stage',choices=['domain','connection','interop','host','lint','instrumentation','apk'])
parser.add_argument('--record',required=True,help='New short evidence record name; must not already exist')
a=parser.parse_args()
if not a.record or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in a.record):parser.error('invalid record name')
record=ROOT/'artifacts/android-phase-2/runs'/a.record
record.mkdir(parents=True,exist_ok=False)
env=os.environ.copy();tools=Path(env.get('NARADA_ANDROID_TOOLS',str(Path.home()/'.cache/narada-android-tools')))
if (tools/'java-home.txt').exists():env['JAVA_HOME']=(tools/'java-home.txt').read_text().strip()
env.setdefault('ANDROID_HOME',str(tools/'sdk'));env.setdefault('ANDROID_USER_HOME',str(tools/'android-user'));env.setdefault('GRADLE_USER_HOME',str(Path.home()/'.cache/narada-android-gradle'))
env['PATH']=env.get('JAVA_HOME','')+'/bin:'+env['PATH'];env['MALLOC_ARENA_MAX']='2'
tasks={'domain':[':core:domain:test'],'connection':[':core:connection:test'],'interop':[':core:connection:interopTest'],'host':[':app:testDebugUnitTest'],'lint':[':app:lintDebug'],'instrumentation':[':app:assembleDebugAndroidTest'],'apk':[':app:assembleDebug']}
heap={'domain':256,'connection':256,'interop':256,'host':256,'lint':384,'instrumentation':640,'apk':640}[a.stage]
args=['./gradlew','--no-daemon','--no-build-cache','--info','--project-cache-dir','.cache/project-gradle',f'-Dorg.gradle.jvmargs=-Xmx{heap}m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8',f'-PnaradaEvidenceDir={record}']+tasks[a.stage]
# Check tasks must really run, without recompiling all prerequisites in this memory-constrained process.
if a.stage=='interop':
    binary=Path(env.get('NARADA_AGENT_BINARY',str(Path.home()/'.cache/narada-rust-phase2/agent-target/debug/narada-agent')))
    if not binary.is_file():raise RuntimeError('Build the current locked Rust agent first; no fixture or skip')
    args.insert(-1,f'-PnaradaAgentBinary={binary}')
    (record/'agent-binary-sha256.txt').write_text(hashlib.sha256(binary.read_bytes()).hexdigest()+'\n')
if a.stage in ('domain','connection','interop','host'):
    marker=PROJECT/({'domain':'core/domain/build/test-results/test','connection':'core/connection/build/test-results/test','interop':'core/connection/build/test-results/interopTest','host':'app/build/test-results/testDebugUnitTest'}[a.stage])
    # Removing only previous TEST OUTPUTS forces task execution; source/compiled inputs are preserved.
    if marker.exists():shutil.rmtree(marker)
sources={}
for p in sorted(PROJECT.rglob('*')):
    if not p.is_file() or any(part in {'build','.cache','.gradle','.kotlin'} for part in p.relative_to(PROJECT).parts) or p.name=='local.properties':continue
    if p.suffix in {'.jks','.keystore'}:continue
    sources[p.relative_to(ROOT).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
(record/'source-hashes.json').write_text(json.dumps(sources,indent=2)+'\n')
started=dt.datetime.now(dt.timezone.utc).isoformat();begin=time.monotonic()
with (record/'gradle.log').open('w') as log:
    result=subprocess.run(args,cwd=PROJECT,env=env,stdout=log,stderr=subprocess.STDOUT)
summary={'stage':a.stage,'started_at_utc':started,'duration_seconds':round(time.monotonic()-begin,3),'exit_code':result.returncode,'command':args,'native_device_execution':False,'emulator_execution':False,'ci_executed':False,'tests':{'passed':0,'failed':0,'errors':0,'skipped':0},'test_suites':[]}
paths=[marker] if a.stage in ('domain','connection','interop','host') else []
for folder in paths:
    if folder.exists():
        shutil.copytree(folder,record/'junit',dirs_exist_ok=True)
        for report in folder.glob('TEST-*.xml'):
            node=ET.parse(report).getroot();counts={key:int(node.get(key,0)) for key in ('tests','failures','errors','skipped')}
            summary['tests']['passed']+=counts['tests']-counts['failures']-counts['errors']-counts['skipped'];summary['tests']['failed']+=counts['failures'];summary['tests']['errors']+=counts['errors'];summary['tests']['skipped']+=counts['skipped']
            summary['test_suites'].append({'name':node.get('name'),**counts})
if a.stage=='lint':
    for filename in ['lint-results-debug.xml','lint-results-debug.html']:
        p=PROJECT/'app/build/reports'/filename
        if p.exists():shutil.copy2(p,record/filename)
summary['source_count']=len(sources)
if a.stage in ('domain','connection','interop','host') and summary['tests']['passed']==0:
    summary['test_output_missing']=True
    if result.returncode==0:result.returncode=1;summary['exit_code']=1
(record/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
print('\n'.join((record/'gradle.log').read_text(errors='replace').splitlines()[-45:]))
raise SystemExit(result.returncode)
