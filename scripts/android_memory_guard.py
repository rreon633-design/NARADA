#!/usr/bin/env python3
"""Build-host guard for this small Linux workspace; not part of the Android app.
Only signals Java processes whose command line contains this workspace's dedicated
narada-android tool/cache path. Run explicitly while developing on a small host.
"""
import os
import signal
import time
from pathlib import Path

print('Android build memory guard active (host only)', flush=True)
while True:
    candidates = []
    for path in Path('/proc').iterdir():
        if not path.name.isdigit():
            continue
        try:
            command = (path / 'cmdline').read_bytes()
            if b'narada-android' not in command or not any(token in command for token in (b'GradleDaemon', b'GradleWorkerMain')):
                continue
            rss = int(next(line for line in (path / 'status').read_text().splitlines() if line.startswith('VmRSS:')).split()[1])
            candidates.append((rss, int(path.name)))
        except (OSError, ValueError, StopIteration):
            pass
    try:
        available = int(next(line for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')).split()[1])
        if candidates:
            rss, pid = max(candidates)
            if rss > 1_300_000 or available < 150_000:
                print(f'Stopping build JVM {pid}: RSS={rss} KiB, available={available} KiB; split or lower the task budget.', flush=True)
                os.kill(pid, signal.SIGKILL)
    except (OSError, ValueError, StopIteration):
        pass
    time.sleep(0.5)
