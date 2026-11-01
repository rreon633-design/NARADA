"""Source-input fingerprints, never execution/independent-review evidence by themselves."""
import hashlib
from pathlib import Path
EXCLUDED={'build','target','dist','out','node_modules','.cache','.gradle','.kotlin','.git','.historical-runtime','__pycache__'}
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def desktop_inputs(root):
    root=Path(root);paths=[root/name for name in ('Cargo.toml','Cargo.lock','rust-toolchain.toml','package.json','package-lock.json')]
    # Full current desktop/peer source, not just the changed extension files or the binary.
    for directory in ('crates','apps/windows-agent','apps/desktop-shell','desktop-ui','config','release'):
        paths.extend(p for p in (root/directory).rglob('*') if p.is_file() and not any(part in EXCLUDED for part in p.relative_to(root).parts))
    return {p.relative_to(root).as_posix():sha(p) for p in sorted(set(paths))}
def android_inputs(root):
    root=Path(root);project=root/'apps/android'
    return {p.relative_to(root).as_posix():sha(p) for p in sorted(project.rglob('*')) if p.is_file() and not any(part in EXCLUDED for part in p.relative_to(project).parts) and p.name!='local.properties' and p.suffix not in {'.jks','.keystore'}}
