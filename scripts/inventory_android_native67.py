#!/usr/bin/env python3
"""Aggregate provided native dependency notices; NOT legal/advisory/independent approval.
Cargo metadata includes platform/build-only packages, not a binary reachability SBOM.
No credentials/private test profiles are read. Run before the final input freeze/build.
"""
import argparse, gzip, hashlib, json, os, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(); p.add_argument('--record', required=True); a = p.parse_args()
assert a.record and all(c.isalnum() or c in '-_' for c in a.record)
output = ROOT / 'artifacts/android-phase-6-7' / (a.record + '.json')
assert not output.exists(), 'Preserve previous inventories; use a fresh record'
def digest(data): return hashlib.sha256(data).hexdigest()
result = {'inventory_scope': 'All locked Cargo metadata packages, including target/build-only dependencies; NOT binary reachability or a reviewed release SBOM.', 'independently_reviewed': False, 'legal_review_complete': False, 'advisory_review_complete': False, 'libraries': {}}
for library in ('age', 'olm'):
    base = ROOT / 'apps/android' / ('native-' + library)
    metadata = json.loads(subprocess.check_output(['cargo', 'metadata', '--locked', '--format-version', '1', '--manifest-path', str(base / 'Cargo.toml')], cwd=ROOT))
    nodes = {n['id']: n for n in metadata['resolve']['nodes']}
    lines = [f'NARADA Android {library} dependency notices', 'Provided notices/declarations from locked packages, including platform/build-only entries. Not an independent legal or security review. NARADA application code remains LicenseRef-Proprietary; no release authorization is implied.', '']
    rows = []
    for package in sorted(metadata['packages'], key=lambda x: (x['name'], x['version'])):
        directory = Path(package['manifest_path']).parent
        files = set()
        for item in directory.iterdir():
            if item.name.upper().startswith(('LICENSE', 'LICENCE', 'COPYING', 'COPYRIGHT', 'NOTICE')):
                files.update([item] if item.is_file() else [p for p in item.rglob('*') if p.is_file()])
        if package.get('license_file'):
            license_file = Path(package['license_file'])
            files.add(license_file if license_file.is_absolute() else directory / license_file)
        row = {'name': package['name'], 'version': package['version'], 'declared_license': package.get('license'), 'source': package.get('source') or 'local application adapter', 'metadata_resolved_features': nodes.get(package['id'], {}).get('features', []), 'provided_license_files': []}
        lines += [f'===== {package["name"]} {package["version"]} =====', 'Declared license: ' + str(package.get('license')), '']
        for file in sorted(files):
            if not file.is_file(): continue
            data = file.read_bytes(); name = file.relative_to(directory).as_posix()
            row['provided_license_files'].append({'filename': name, 'sha256': digest(data), 'bytes': len(data)})
            lines += ['--- ' + name + ' ---', data.decode('utf-8', errors='replace'), '']
        row['supplemental_notice_files'] = []
        for file in sorted((base / 'notices').glob(package['name'] + '-' + package['version'] + '-*.txt')):
            data = file.read_bytes()
            row['supplemental_notice_files'].append({'file': file.relative_to(ROOT).as_posix(), 'sha256': digest(data), 'bytes': len(data)})
            lines += ['--- Upstream immutable-commit supplemental notice: ' + file.name + ' ---', data.decode('utf-8'), '']
        if not row['provided_license_files'] and not row['supplemental_notice_files']:
            lines += ['No separate license text was found in the package paths scanned. The declaration above is not an independent license conclusion; resolve obligations before release.', '']
        rows.append(row)
    notice = ('\n'.join(lines) + '\n').encode()
    compressed = gzip.compress(notice, compresslevel=9, mtime=0)
    asset = ROOT / 'apps/android/app/src/main/assets' / ('native-' + library + '-notices.txt.gz')
    asset.write_bytes(compressed)
    result['libraries'][library] = {'cargo_lock_sha256': digest((base / 'Cargo.lock').read_bytes()), 'packages': rows, 'package_count': len(rows), 'notice_asset': asset.relative_to(ROOT).as_posix(), 'notice_asset_sha256': digest(compressed), 'notice_sha256': digest(notice), 'notice_bytes': len(notice), 'packages_without_separate_license_files': [r['name'] + '@' + r['version'] for r in rows if not r['provided_license_files'] and not r['supplemental_notice_files']]}
output.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'record': output.relative_to(ROOT).as_posix(), 'sha256': digest(output.read_bytes()), 'package_counts': {k: v['package_count'] for k, v in result['libraries'].items()}, 'independently_reviewed': False}, indent=2))
