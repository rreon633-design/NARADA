#!/usr/bin/env python3
"""Isolate restored NSIS outputs before a fresh Windows build; never select stale candidates.
Only the generated bundle directory is moved. Prior bytes are inventoried/read back,
not overwritten/deleted; source, signing identities and application state are untouched.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
from evidence import ROOT, digest, output, require, write_json
from artifacts import context

BUNDLE = 'target/release/bundle/nsis'
DISPLACED = '.cache/ci-displaced-windows-bundles/nsis'
MAX_FILES = 256
MAX_BYTES = 128 * 1024 * 1024


def no_links(root, path):
    require(path.is_relative_to(root), 'Bundle preparation must stay inside the checkout')
    for p in [path, *path.parents]:
        require(not p.is_symlink() and not getattr(p, 'is_junction', lambda: False)(), 'Refusing symlink/junction in bundle preparation')
        if p == root: break


def inventory(root, folder):
    rows = {}
    total = 0
    if not folder.exists(): return rows
    require(folder.is_dir(), 'Bundle output is not a directory')
    for base, dirs, files in os.walk(folder, followlinks=False):
        for name in dirs + files: no_links(root, Path(base) / name)
        for name in sorted(files):
            path = Path(base) / name
            require(path.is_file(), 'Unexpected non-regular generated output')
            size = path.stat().st_size
            total += size
            require(len(rows) < MAX_FILES and total <= MAX_BYTES, 'Restored bundle inventory exceeds its bound')
            rows[path.relative_to(folder).as_posix()] = {'bytes': size, 'sha256': digest(path)}
    return dict(sorted(rows.items()))


def prepare_bundle(root, evidence_dir, source_context):
    root, evidence_dir = Path(root), Path(evidence_dir)
    source, displaced = root / BUNDLE, root / DISPLACED
    before, after = evidence_dir / 'windows-bundle-before.json', evidence_dir / 'windows-bundle-preparation.json'
    for path in (source, displaced, before, after): no_links(root, path)
    require(not before.exists() and not after.exists(), 'Never overwrite bundle-preparation evidence')
    require(not displaced.exists(), 'Displaced bundle already exists; review rather than overwrite')
    rows = inventory(root, source)
    existed = source.exists()
    evidence_dir.mkdir(parents=True, exist_ok=True)
    write_json(before, {'context': source_context, 'source_directory': BUNDLE, 'existed': existed, 'files': rows})
    if existed:
        displaced.parent.mkdir(parents=True, exist_ok=True)
        source.rename(displaced)
        require(inventory(root, displaced) == rows, 'Displaced cached bytes changed during isolation')
    source.mkdir(parents=True, exist_ok=True)
    require(not any(source.iterdir()), 'Fresh NSIS output directory is not empty')
    record = {'schema': 1, 'context': source_context, 'source_directory': BUNDLE,
              'displaced_directory': DISPLACED if existed else None, 'prior_files': rows,
              'prior_bytes_preserved': True, 'fresh_output_empty': True,
              'scope': 'Restored generated outputs are not current candidates; native build/audit/install gates still required.'}
    write_json(after, record)
    return record


def main():
    require(os.name == 'nt' and os.environ.get('CI') == 'true' and os.environ.get('NARADA_CI_JOB') == 'windows', 'Use only in the fresh Windows CI campaign')
    record = prepare_bundle(ROOT, output(), context())
    public = {'restored_files_moved': len(record['prior_files']), 'restored_installers': [
        {'name': Path(name).name, **row} for name, row in record['prior_files'].items()
        if '/' not in name and name.endswith('.exe')][:8], 'fresh_output_empty': True,
        'scope': 'Cached files isolated, not qualified or published; new build/runtime gates still required.'}
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        from diagnostics import escaped
        print('::notice title=NARADA fresh Windows bundle boundary::' + escaped(json.dumps(public, separators=(',', ':'))), flush=True)
    else:
        print(json.dumps(public, indent=2))


if __name__ == '__main__': main()
