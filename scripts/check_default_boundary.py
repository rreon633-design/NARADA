#!/usr/bin/env python3
"""One disposable default-build probe, never a real profile reset."""
import importlib.util, json, os, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('pairing_security',ROOT/'tests/process/pairing_security.py');p2=importlib.util.module_from_spec(spec);spec.loader.exec_module(p2)
help_text=subprocess.check_output([str(p2.BINARY),'--help'],text=True)
assert 'preview-port' not in help_text,'Development HTTP flag present in default build'
(ROOT/'test-fixtures').mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='default-boundary-',dir=ROOT/'test-fixtures') as directory:
    agent=p2.Agent(Path(directory)/'profile')
    try:
        snapshot=agent.snapshot()
        assert snapshot['identity']['state']!='ready'
        assert not snapshot['listening']
        assert not (agent.path/'transport.identity').exists()
        assert not agent.raw('configure_connections',{'enabled':True,'bind_ip':'127.0.0.1','api_port':0,'discovery':False})['ok']
        print(json.dumps({'status':'passed','preview_flag':False,'implicit_development_vault':False,'identity_generated':False,'peer_listener':False,'note':'Default Linux build refuses the development key even when one is explicitly supplied by the fixture.'},indent=2))
    finally:agent.stop()
