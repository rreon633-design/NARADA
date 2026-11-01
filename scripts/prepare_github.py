#!/usr/bin/env python3
"""Export a clean source-only Git staging tree without touching preserved handoffs.
This tool never initializes/pushes Git, creates tags, or reads the SSH private key.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_DIRS = {'.git', '.ssh', '.cache', '.gradle', '.historical-runtime', '.narada-data',
                 'node_modules', 'target', 'build', 'dist', 'out', '__pycache__', '.pytest_cache',
                 'test-fixtures', 'test-results', 'playwright-report', 'ci-results', 'release-assets'}
EXCLUDED_SUFFIXES = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.key', '.log',
                     '.db', '.db-shm', '.db-wal', '.pyc', '.exe', '.dll', '.so', '.bsdiff'}
WORKFLOW = '.github/workflows/build-test-release.yml'
PRIVATE_KEY = re.compile(rb'-----BEGIN (?:OPENSSH |RSA |EC |DSA |ENCRYPTED )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}-----END')
TOKEN = re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{60,})\b')


def allowed(name):
    p = Path(name)
    if any(part in EXCLUDED_DIRS for part in p.parts): return False
    if p.parts[0] == 'artifacts': return False
    if p.suffix.lower() in EXCLUDED_SUFFIXES: return False
    if p.name.startswith('.env') or p.name == 'local.properties': return False
    if p.name.startswith('SOURCE-MANIFEST') or p.name == 'GITHUB-SOURCE-MANIFEST.json': return False
    if name.startswith('apps/desktop-shell/src-tauri/binaries/'): return False
    if name.startswith('apps/desktop-shell/src-tauri/gen/'): return False
    # Old workflows remain byte-for-byte in the handoff; only the fresh campaign
    # is activated in this first Git repository.
    if name.startswith('.github/workflows/') and name not in {WORKFLOW, '.github/workflows/ci-diagnostics.yml'}: return False
    return True


def plan():
    rows = {}
    for directory, dirs, files in os.walk(ROOT, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d not in EXCLUDED_DIRS and not (Path(directory) == ROOT and d == 'artifacts'))
        for filename in sorted(files):
            path = Path(directory) / filename
            name = path.relative_to(ROOT).as_posix()
            if not allowed(name): continue
            if path.is_symlink(): raise RuntimeError(f'Refusing source symlink: {name}')
            if path.stat().st_size > 10 * 1024 * 1024: raise RuntimeError(f'Oversized source input: {name}')
            data = path.read_bytes()
            if PRIVATE_KEY.search(data) or TOKEN.search(data): raise RuntimeError(f'Possible credential in {name}; not exported')
            rows[name] = (path, data)
    template = ROOT / 'release/github-readme.md.in'
    if not template.is_file(): raise RuntimeError('Missing GitHub README template')
    rows['README.md'] = (template, template.read_bytes())
    required = [WORKFLOW, 'Cargo.lock', 'package-lock.json', 'apps/android/gradle/wrapper/gradle-wrapper.jar',
                'apps/android/gradle/verification-metadata.xml', 'scripts/ci/publish.py',
                'apps/android/app/src/manualTest/res/values/strings.xml',
                'apps/desktop-shell/src-tauri/icons/icon.ico', 'tests/fixtures/file-preview.png']
    for name in required:
        if name not in rows: raise RuntimeError(f'Missing required source: {name}')
    total = sum(len(data) for _, data in rows.values())
    if total > 25 * 1024 * 1024: raise RuntimeError('Clean source exceeds the 25 MiB repository-preparation guardrail')
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', help='A new directory under .cache; never a live repository')
    args = parser.parse_args()
    rows = plan()
    report = {'repository': 'https://github.com/rreon633-design/NARADA', 'intended_initial_tag': 'v0.0.1',
              'files': len(rows), 'bytes': sum(len(data) for _, data in rows.values()),
              'history': 'Original archives and campaigns remain in the handoff workspace, not relabeled as new CI.',
              'push_performed': False,
              'inputs': {name: {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data),
                                'source': path.relative_to(ROOT).as_posix()}
                         for name, (path, data) in sorted(rows.items())}}
    if args.destination:
        dest = (ROOT / args.destination).resolve()
        cache = (ROOT / '.cache').resolve()
        if not dest.is_relative_to(cache) or dest == cache or dest.exists():
            raise RuntimeError('Destination must be a new directory beneath this project .cache')
        dest.mkdir(parents=True)
        for name, (_, data) in sorted(rows.items()):
            path = dest / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            path.chmod(0o755 if name == 'apps/android/gradlew' else 0o644)
        (dest / 'GITHUB-SOURCE-MANIFEST.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        for name, row in report['inputs'].items():
            if hashlib.sha256((dest / name).read_bytes()).hexdigest() != row['sha256']:
                raise RuntimeError('Copy validation failed: ' + name)
        print('Source-only staging tree: ' + str(dest))
    print(json.dumps({k: v for k, v in report.items() if k != 'inputs'}, indent=2))


if __name__ == '__main__': main()
