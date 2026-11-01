"""The executable/configuration source boundary used by validation and release.
Approval packets and generated evidence are not code; they are verified separately.
"""
import hashlib
import os
from pathlib import Path
PREFIXES=['crates','apps','desktop-ui','scripts','tests','.github','release','fuzz']
SUFFIXES={'.rs','.js','.mjs','.css','.sql','.toml','.json','.py','.yml','.yaml','.ps1','.html','.txt'}
EXCLUDED={'node_modules','target','dist','gen','binaries','__pycache__','build','.cache'}
ROOT_FILES=['Cargo.toml','Cargo.lock','package.json','package-lock.json','playwright.config.js','playwright.desktop-5-9.config.js','rust-toolchain.toml']
def capture(root):
    root=Path(root).resolve();result={}
    for prefix in PREFIXES:
        for parent,dirs,files in os.walk(root/prefix):
            dirs[:]=[name for name in dirs if name not in EXCLUDED]
            for name in files:
                path=Path(parent)/name;relative=path.relative_to(root).as_posix()
                if relative=='release/qualification.json':continue
                if path.suffix in SUFFIXES:
                    if path.is_symlink():raise ValueError('Source symlinks require explicit review: '+relative)
                    result[relative]=hashlib.sha256(path.read_bytes()).hexdigest()
    for name in ROOT_FILES:result[name]=hashlib.sha256((root/name).read_bytes()).hexdigest()
    return dict(sorted(result.items()))
