#!/usr/bin/env python3
"""Build the tiny filesystem-only JNI bridge; no third-party native dependency.
Android outputs use the pinned NDK/API29; host output is only for JVM regressions.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'apps/android/native-storage/storage.c'
BASE = ROOT / 'apps/android/app/build/generated/storage'
NDK_VERSION = '30.0.16248370'
TARGETS = {'arm64-v8a': 'aarch64-linux-android29-clang',
           'armeabi-v7a': 'armv7a-linux-androideabi29-clang',
           'x86_64': 'x86_64-linux-android29-clang',
           'x86': 'i686-linux-android29-clang'}

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', action='store_true')
    parser.add_argument('--android', action='store_true')
    args = parser.parse_args()
    assert args.host or args.android, 'Select host and/or Android explicitly'
    assert platform.system() == 'Linux' and platform.machine() == 'x86_64'
    paths = [SOURCE, Path(__file__).resolve(), ROOT / 'apps/android/app/src/main/assets/native-storage-notices.txt.gz']
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in paths}
    common = ['-std=c11', '-Os', '-fPIC', '-fvisibility=hidden', '-fstack-protector-strong',
              '-D_FORTIFY_SOURCE=2', '-ffunction-sections', '-fdata-sections',
              '-Wall', '-Wextra', '-Werror', '-shared',
              '-Wl,--gc-sections,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384',
              '-Wl,-soname,libnarada_storage.so']
    rows = []
    commands = []
    started = time.monotonic()
    def build(abi, compiler, includes, destination):
        destination.parent.mkdir(parents=True, exist_ok=True)
        command = [str(compiler), *common, *includes, str(SOURCE), '-o', str(destination)]
        subprocess.run(command, check=True)
        rows.append({'abi': abi, 'path': destination.relative_to(ROOT).as_posix(),
                     'bytes': destination.stat().st_size, 'sha256': sha(destination)})
        commands.append({'abi': abi, 'command': command,
                         'compiler_sha256': sha(Path(compiler).resolve()),
                         'compiler_version': subprocess.check_output([str(compiler), '--version'], text=True).splitlines()[0]})
    if args.host:
        jdk = Path(os.environ['JAVA_HOME'])
        assert (jdk / 'include/jni.h').is_file()
        compiler = shutil.which('cc')
        assert compiler, 'A Linux host C compiler is required for JNI regression tests'
        build('linux-x86_64-host', compiler, ['-I' + str(jdk / 'include'), '-I' + str(jdk / 'include/linux')], BASE / 'host/libnarada_storage.so')
    if args.android:
        ndk = Path(os.environ['ANDROID_NDK_HOME'])
        assert 'Pkg.Revision = ' + NDK_VERSION in (ndk / 'source.properties').read_text()
        tools = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        for abi, name in TARGETS.items():
            build(abi, tools / name, [], BASE / 'jniLibs' / abi / 'libnarada_storage.so')
    assert before == {p.relative_to(ROOT).as_posix(): sha(p) for p in paths}, 'Source changed while building'
    result = {'bridge_version': 1, 'mode': 'Pinned-directory renameat2(RENAME_NOREPLACE); no hard-link or overwrite fallback',
              'ndk': NDK_VERSION if args.android else None, 'source_sha256': before,
              'libraries': rows, 'commands': commands, 'duration_seconds': round(time.monotonic() - started, 3),
              'android_executed': False, 'independently_reviewed': False}
    BASE.mkdir(parents=True, exist_ok=True)
    (BASE / 'build-receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))

if __name__ == '__main__':
    main()
