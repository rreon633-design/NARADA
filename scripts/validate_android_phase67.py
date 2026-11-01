#!/usr/bin/env python3
"""Run one Android Phases 6–7 validation stage into a NEW evidence directory. Never touches desktop evidence."""
import argparse, datetime as dt, hashlib, json, os, shutil, subprocess, time
from pathlib import Path
import xml.etree.ElementTree as ET
from android_phase67_inputs import desktop_inputs, android_inputs, sha
ROOT=Path(__file__).resolve().parents[1]
PROJECT=ROOT/'apps/android'
parser=argparse.ArgumentParser()
parser.add_argument('stage',choices=['domain','connection','interop','host','lint','instrumentation','apk'])
parser.add_argument('--record',required=True,help='New short evidence record name; must not already exist')
parser.add_argument('--test-filter',help='Optional explicit preparation-only Gradle test filter; final audit requires full suite counts')
parser.add_argument('--peer-receipt', default='artifacts/android-phase-6-7/peer-builds/peer-01/receipt.json', help='Source-bound current Linux peer build receipt for actual-peer tests')
a=parser.parse_args()
if not a.record or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in a.record):parser.error('invalid record name')
record=ROOT/'artifacts/android-phase-6-7/runs'/a.record
record.mkdir(parents=True,exist_ok=False)
env=os.environ.copy();tools=Path(env.get('NARADA_ANDROID_TOOLS',str(Path.home()/'.cache/narada-android-tools')))
if (tools/'java-home.txt').exists():env['JAVA_HOME']=(tools/'java-home.txt').read_text().strip()
env.setdefault('ANDROID_HOME',str(tools/'sdk'));env.setdefault('ANDROID_USER_HOME',str(tools/'android-user'));env.setdefault('GRADLE_USER_HOME',str(Path.home()/'.cache/narada-android-gradle'))
env['PATH']=env.get('JAVA_HOME','')+'/bin:'+env['PATH'];env['MALLOC_ARENA_MAX']='2'
tasks={'domain':[':core:domain:test'],'connection':[':core:connection:test'],'interop':[':core:connection:interopTest'],'host':[':app:testDebugUnitTest'],'lint':[':app:lintDebug'],'instrumentation':[':app:assembleDebugAndroidTest'],'apk':[':app:assembleDebug']}
heap={'domain':256,'connection':256,'interop':256,'host':160,'lint':512,'instrumentation':640,'apk':640}[a.stage]
metaspace=224 if a.stage=='host' else 256 if a.stage=='lint' else 320
args=['./gradlew','--no-daemon','--no-build-cache','--info','--project-cache-dir','.cache/project-gradle',f'-Dorg.gradle.jvmargs=-Xmx{heap}m -XX:MaxMetaspaceSize={metaspace}m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8',f'-PnaradaEvidenceDir={record}']+tasks[a.stage]
if a.test_filter:
    if a.stage not in ('host','connection','interop','domain'):parser.error('test filters only apply to test stages')
    args += ['--tests',a.test_filter]
# Check tasks must really run, without recompiling all prerequisites in this memory-constrained process.
if a.stage=='interop':
    binary=Path(env.get('NARADA_AGENT_BINARY',str(Path.home()/'.cache/narada-rust-phase2/agent-target/debug/narada-agent')))
    if not binary.is_file():raise RuntimeError('Build the current locked Rust agent first; no fixture or skip')
    peer_receipt=ROOT/a.peer_receipt
    peer=json.loads(peer_receipt.read_text())
    assert peer['exit_code']==0 and peer['binary_sha256']==sha(binary)
    assert peer['desktop_source_sha256_before_and_after']==desktop_inputs(ROOT), 'Rebuild the current peer; source drift is not interoperable evidence'
    shutil.copy2(peer_receipt,record/'peer-build-receipt.json')
    (record/'desktop-source-hashes.json').write_text(json.dumps(desktop_inputs(ROOT),indent=2)+'\n')
    args.insert(1,f'-PnaradaAgentBinary={binary}')
    (record/'agent-binary-sha256.txt').write_text(sha(binary)+'\n')
if a.stage in ('domain','connection','interop','host'):
    marker=PROJECT/({'domain':'core/domain/build/test-results/test','connection':'core/connection/build/test-results/test','interop':'core/connection/build/test-results/interopTest','host':'app/build/test-results/testDebugUnitTest'}[a.stage])
    # Removing only previous TEST OUTPUTS forces task execution; source/compiled inputs are preserved.
    if marker.exists():shutil.rmtree(marker)
if a.stage=='lint':
    # A failed/interrupted lint must not copy an older build's report as new evidence.
    for filename in ('lint-results-debug.xml','lint-results-debug.html'):
        stale=PROJECT/'app/build/reports'/filename
        if stale.exists():stale.unlink()
sources=android_inputs(ROOT)
(record/'source-hashes.json').write_text(json.dumps(sources,indent=2)+'\n')
for library in ('olm','age'):
 native=PROJECT/('app/build/generated/'+library+'/build-receipt.json')
 if native.exists():
     receipt=json.loads(native.read_text())
     for name,digest in receipt['source_sha256'].items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest
     for item in receipt['libraries']:assert hashlib.sha256((ROOT/item['path']).read_bytes()).hexdigest()==item['sha256']
     shutil.copy2(native,record/('native-'+library+'-build-receipt.json'))
started=dt.datetime.now(dt.timezone.utc).isoformat();begin=time.monotonic()
# Kotlin compilation and a native-graphics test worker must not share a large compiler heap.
# Compile in a separate bounded process; the test-only daemon then needs less resident memory.
precompile=None
if a.stage=='host':
    precompile=['./gradlew','--no-daemon','--max-workers=1','--info','--no-build-cache','--project-cache-dir','.cache/project-gradle','-Dorg.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8',':app:compileDebugUnitTestKotlin']
with (record/'gradle.log').open('w') as log:
    if precompile:
        log.write('Separate host compilation preflight: '+repr(precompile)+'\n');log.flush()
        result=subprocess.run(precompile,cwd=PROJECT,env=env,stdout=log,stderr=subprocess.STDOUT)
    if not precompile or result.returncode==0:
        result=subprocess.run(args,cwd=PROJECT,env=env,stdout=log,stderr=subprocess.STDOUT)
summary={'stage':a.stage,'started_at_utc':started,'duration_seconds':round(time.monotonic()-begin,3),'exit_code':result.returncode,'command':args,'native_device_execution':False,'emulator_execution':False,'ci_executed':False,'tests':{'passed':0,'failed':0,'errors':0,'skipped':0},'test_suites':[]}
if precompile:summary['precompile_command']=precompile
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
assert sources==android_inputs(ROOT), 'Android inputs changed during the stage'
if a.stage=='interop':assert peer['desktop_source_sha256_before_and_after']==desktop_inputs(ROOT) and peer['binary_sha256']==sha(binary)
summary['source_count']=len(sources)
summary['source_before_after_identical']=True
summary['tool_sha256']={name:sha(ROOT/name) for name in ('scripts/validate_android_phase67.py','scripts/android_phase67_inputs.py','scripts/build_android_age.py','scripts/build_android_olm67.py')}
for name,digest in summary['tool_sha256'].items():
    dest=record/'tool-sources'/Path(name).name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,dest);assert sha(dest)==digest
summary['source_manifest_sha256']=sha(record/'source-hashes.json')
summary['gradle_log_sha256']=sha(record/'gradle.log')
summary['test_filter']=a.test_filter
if a.stage in ('domain','connection','interop','host') and not summary['test_suites']:
    summary['test_output_missing']=True
    if result.returncode==0:result.returncode=1;summary['exit_code']=1
(record/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
print('\n'.join((record/'gradle.log').read_text(errors='replace').splitlines()[-45:]))
raise SystemExit(result.returncode)
