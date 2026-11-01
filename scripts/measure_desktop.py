#!/usr/bin/env python3
"""One Linux host observation, not native performance or a benchmark certification."""
import importlib.util,json,os,statistics,tempfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];spec=importlib.util.spec_from_file_location('p2',ROOT/'tests/process/pairing_security.py');p2=importlib.util.module_from_spec(spec);spec.loader.exec_module(p2)
OUT=ROOT/'artifacts/desktop-5-9/resource-report.json'
def process_stats(pid):
    fields=Path(f'/proc/{pid}/stat').read_text().split();ticks=int(fields[13])+int(fields[14]);status=Path(f'/proc/{pid}/status').read_text().splitlines();memory={k:int(next(line.split()[1] for line in status if line.startswith(k+':')))*1024 for k in ['VmRSS','VmHWM']};return ticks,memory
(ROOT/'test-fixtures').mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='resources-',dir=ROOT/'test-fixtures') as base:
    started=time.perf_counter();agent=p2.Agent(Path(base)/'agent');startup=time.perf_counter()-started
    try:
        initial,_=process_stats(agent.process.pid);begin=time.monotonic();time.sleep(2);end,memory=process_stats(agent.process.pid);duration=time.monotonic()-begin;latency=[];writes=[]
        for _ in range(30):
            t=time.perf_counter();agent.call('get_snapshot');latency.append((time.perf_counter()-t)*1000)
        for i in range(20):
            t=time.perf_counter();agent.call('save_chat_draft',{'peer_id':'a'*64,'text':f'Synthetic protected resource fixture {i}'});writes.append((time.perf_counter()-t)*1000)
        _,peak=process_stats(agent.process.pid);status=agent.call('content_status')
        report={'application':'NARADA','version':'0.7.0','status':'measured','host':'Linux sandbox, one disposable agent, no physical LAN','scope':'30 owner snapshots and 20 protected draft commits; idle sample only. No inference about native Windows, 64 MiB transfers, battery life or installer performance.','startup_to_owner_rpc_ms':round(startup*1000,3),'idle_sample_seconds':round(duration,3),'idle_process_cpu_percent_one_core':round((end-initial)/os.sysconf('SC_CLK_TCK')/duration*100,3),'resident_bytes':memory['VmRSS'],'peak_resident_bytes':peak['VmHWM'],'owner_rpc_ms':{'median':round(statistics.median(latency),3),'max':round(max(latency),3),'samples':len(latency)},'protected_draft_commit_ms':{'median':round(statistics.median(writes),3),'max':round(max(writes),3),'samples':len(writes)},'protected_store_ready':status['ready'],'private_store_file_bytes':sum(p.stat().st_size for p in agent.path.iterdir() if p.name.startswith('content.') and p.is_file()),'release_qualified':False}
        OUT.parent.mkdir(parents=True,exist_ok=True);OUT.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
    finally:agent.stop()
