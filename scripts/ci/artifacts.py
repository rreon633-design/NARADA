#!/usr/bin/env python3
"""Audit and collect NEW lightweight candidates; never packages historical binaries."""
from __future__ import annotations
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from evidence import ROOT, digest, output, require, write_json

POLICY = json.loads((ROOT / "release/ci-policy.json").read_text(encoding="utf-8"))
ABI_MACHINE = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86_64": 62, "x86": 3}


def release_label():
    ref = os.environ.get("GITHUB_REF", "")
    if ref.startswith("refs/tags/"):
        tag = ref.removeprefix("refs/tags/")
        require(re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", tag), "Invalid version tag")
        return tag
    sha = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", sha), "Missing source commit")
    return "dev-" + sha[:12]


def command(args):
    return subprocess.check_output([str(a) for a in args], stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")


def abi_in_zip(names):
    abis = {n.split("/")[1] for n in names if n.startswith("lib/") and n.endswith(".so") and len(n.split("/")) == 3}
    require(len(abis) == 1 and abis <= ABI_MACHINE.keys(), f"Expected one supported ABI, got {abis}")
    return next(iter(abis))


def elf_check(data, abi):
    require(data[:4] == b"\x7fELF" and len(data) >= 52 and data[5] == 1, "Invalid/non-little-endian ELF")
    bits = data[4]
    require(bits in (1, 2), "Unknown ELF class")
    require(struct.unpack_from("<H", data, 18)[0] == ABI_MACHINE[abi], "Wrong ELF architecture")
    require(bits == (2 if abi in ("arm64-v8a", "x86_64") else 1), "Wrong ELF word size")
    if bits == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        minimum = 56
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
        minimum = 32
    require(size >= minimum and count > 0 and offset + count * size <= len(data), "Invalid ELF program headers")
    alignments = []
    for index in range(count):
        p = offset + size * index
        if struct.unpack_from("<I", data, p)[0] == 1:
            align = struct.unpack_from("<Q" if bits == 2 else "<I", data, p + (48 if bits == 2 else 28))[0]
            if bits == 2:
                require(align >= 16384 and align & (align - 1) == 0, "64-bit ELF is not statically 16 KiB-page aligned")
            alignments.append(align)
    require(bool(alignments), "No ELF load segments")
    return {"elf_bits": bits * 32, "load_alignments": alignments}


def pe_machine(path):
    with Path(path).open("rb") as f:
        head = f.read(64)
        require(len(head) == 64 and head[:2] == b"MZ", "Invalid PE DOS header")
        offset = struct.unpack_from("<I", head, 60)[0]
        require(offset < Path(path).stat().st_size - 6, "Invalid PE offset")
        f.seek(offset)
        pe = f.read(6)
    require(pe[:4] == b"PE\0\0", "Invalid PE signature")
    return struct.unpack_from("<H", pe, 4)[0]


def file_row(path):
    return {"name": Path(path).name, "bytes": Path(path).stat().st_size, "sha256": digest(path)}


def package_dir(platform):
    dest = ROOT / ".cache/ci-packages" / platform
    dest.mkdir(parents=True, exist_ok=False)
    return dest


def write_checksums(dest, name):
    rows = [p for p in sorted(dest.iterdir()) if p.is_file()]
    require(rows and all(not p.is_symlink() for p in rows), "Invalid package contents")
    with (dest / name).open("x", encoding="utf-8", newline="\n") as stream:
        for p in rows:
            stream.write(f"{digest(p)}  {p.name}\n")


def context():
    value = json.loads((output() / "context.json").read_text(encoding="utf-8"))
    return {"commit": value["commit"], "run_id": value["run_id"], "run_attempt": value["run_attempt"],
            "source_inputs_sha256": value["source_inputs_sha256"], "channel": POLICY["channel"]}


def frontend():
    base = ROOT / "desktop-ui/dist"
    files = sorted(p for p in base.rglob("*") if p.is_file())
    require(files and (base / "index.html").is_file(), "Missing frontend build")
    require(not any(p.suffix == ".map" for p in files), "Release source maps must not be bundled")
    size = sum(p.stat().st_size for p in files)
    require(size <= POLICY["frontend_max_bytes"], f"Frontend budget exceeded: {size} bytes")
    return {"bytes": size, "budget_bytes": POLICY["frontend_max_bytes"],
            "files": {p.relative_to(base).as_posix(): file_row(p) for p in files}}


def windows():
    require(os.name == "nt", "Windows candidates must be built on Windows")
    installers = sorted((ROOT / "target/release/bundle/nsis").glob("*.exe"))
    require(len(installers) == 1, "Expected exactly one newly built NSIS installer")
    exe = ROOT / "target/release/narada-desktop.exe"
    agent = ROOT / "target/release/narada-agent.exe"
    require(pe_machine(exe) == pe_machine(agent) == 0x8664, "Expected x64 native Windows binaries")
    require("preview-port" not in command([agent, "--help"]), "Development HTTP bridge leaked into native binary")
    config = json.loads((ROOT / "apps/desktop-shell/src-tauri/tauri.conf.json").read_text(encoding="utf-8"))
    require(config["bundle"]["windows"]["webviewInstallMode"]["type"] == "downloadBootstrapper", "Do not bundle a Chromium/WebView runtime")
    require(installers[0].stat().st_size <= POLICY["windows_installer_max_bytes"], "Windows installer size budget exceeded")
    dest = package_dir("windows")
    target = dest / f"NARADA-{release_label()}-windows-x64-setup.exe"
    shutil.copy2(installers[0], target)
    report = {**context(), "signing": POLICY["signing"]["windows"], "files": [file_row(target)],
              "installer_budget_bytes": POLICY["windows_installer_max_bytes"],
              "native_binaries": [file_row(exe), file_row(agent)], "frontend": frontend(),
              "webview": "system Evergreen WebView2; installer downloads bootstrapper only if needed",
              "application_version": config["version"], "unqualified": POLICY["not_qualified"]}
    write_json(dest / "windows-build-report.json", report)
    shutil.copy2(ROOT / "docs/github/manual-testing.md", dest / "WINDOWS-MANUAL-TEST.md")
    write_checksums(dest, "WINDOWS-SHA256SUMS.txt")
    print(json.dumps(report, indent=2))


def android():
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    tools = sdk / "build-tools/35.0.0"
    require((tools / "apksigner").is_file(), "Pinned Android build tools required")
    analyzer = shutil.which("apkanalyzer")
    if analyzer is None:
        candidates = sorted((sdk / "cmdline-tools").glob("*/bin/apkanalyzer"))
        require(bool(candidates), "Android command-line manifest analyzer missing")
        analyzer = str(candidates[-1])
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", str(sdk / "ndk/30.0.16248370")))
    require("Pkg.Revision = 30.0.16248370" in (ndk / "source.properties").read_text(), "Unexpected native strip toolchain")
    llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    require((llvm / "llvm-strip").is_file(), "Pinned NDK strip tool missing")
    apk_dir = ROOT / "apps/android/app/build/outputs/apk/manualTest"
    apks = sorted(apk_dir.rglob("*.apk"))
    require(len(apks) == len(POLICY["android_abis"]), "Expected exactly four per-ABI manual-test APKs, no universal APK")
    dest = package_dir("android")
    emu = ROOT / ".cache/ci-emulator-inputs"
    emu.mkdir(parents=True, exist_ok=False)
    reports, seen, signers = [], set(), set()
    receipt_maps = {}
    for lib in ("age", "olm"):
        receipt = json.loads((ROOT / f"apps/android/app/build/generated/{lib}/build-receipt.json").read_text())
        require(receipt["ndk"] == "30.0.16248370", "Unexpected NDK provenance")
        for source, sha in receipt["source_sha256"].items():
            require(digest(ROOT / source) == sha, "Stale native source provenance")
        receipt_maps[lib] = {r["abi"]: r for r in receipt["libraries"]}
    for apk in apks:
        require(apk.stat().st_size <= POLICY["android_apk_max_bytes"], f"APK exceeds size budget: {apk.name}")
        signature = command([tools / "apksigner", "verify", "--verbose", "--print-certs", apk])
        signer = re.findall(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", signature)
        require(len(signer) == 1, "Missing APK signer fingerprint")
        signers.add(signer[0].lower())
        require("Verified using v2 scheme (APK Signature Scheme v2): true" in signature, "APK v2 signature required")
        command([tools / "zipalign", "-c", "-P", "16", "4", apk])
        badging = command([tools / "aapt2", "dump", "badging", apk])
        require(f"package: name='{POLICY['android_application_id']}'" in badging, "Wrong manual-test package identity")
        require(f"versionCode='{POLICY['android_version_code']}'" in badging and f"versionName='{POLICY['android_version_name']}'" in badging, "Unexpected Android application version")
        require("sdkVersion:'29'" in badging and "targetSdkVersion:'36'" in badging, "Unexpected Android SDK bounds")
        require("application-debuggable" not in badging, "Manual-test candidate must be release-style, not debuggable")
        manifest_text = command([analyzer, "manifest", "print", apk])
        manifest = ET.fromstring(manifest_text)
        application = manifest.find("application")
        require(application is not None, "Missing APK application manifest")
        namespace = "{http://schemas.android.com/apk/res/android}"
        require(application.attrib.get(namespace + "testOnly", "false") == "false", "Manual download must be installable without test-only ADB flags")
        require(application.attrib.get(namespace + "debuggable", "false") == "false", "APK is unexpectedly debuggable")
        for attribute in ("allowBackup", "usesCleartextTraffic"):
            require(application.attrib.get(namespace + attribute) == "false", "Required manifest boundary missing: " + attribute)
        with zipfile.ZipFile(apk) as z:
            names = z.namelist()
            require(len(names) == len(set(names)), "Duplicate APK archive entries")
            require(all(not n.startswith("/") and ".." not in Path(n).parts for n in names), "Unsafe APK path")
            abi = abi_in_zip(names)
            require(abi not in seen, "Duplicate ABI output")
            seen.add(abi)
            native = {}
            for n in names:
                if n.startswith("lib/") and n.endswith(".so"):
                    data = z.read(n)
                    native[n] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), **elf_check(data, abi)}
            for lib in ("age", "olm"):
                n = f"lib/{abi}/libnarada_{lib}.so"
                require(n in native, "Missing mandatory crypto JNI library")
                original = receipt_maps[lib][abi]
                source_binary = ROOT / original["path"]
                require(digest(source_binary) == original["sha256"], "Native build receipt no longer describes its input")
                intermediates = ROOT / "apps/android/app/build/intermediates"
                merged = intermediates / "merged_native_libs/manualTest/mergeManualTestNativeLibs/out" / n
                stripped_by_agp = intermediates / "stripped_native_libs/manualTest/stripManualTestDebugSymbols/out" / n
                require(digest(merged) == original["sha256"], "Merged JNI differs from receipted input")
                require(digest(stripped_by_agp) == native[n]["sha256"], "APK JNI differs from AGP output")
                # AGP legitimately applies NDK stripping AFTER Cargo's stripping.
                # Reproduce the transformation instead of falsely requiring equal
                # hashes between the pre-strip input and the packaged library.
                reproduced = ROOT / ".cache/ci-native-audit" / abi / f"libnarada_{lib}.so"
                reproduced.parent.mkdir(parents=True, exist_ok=True)
                command([llvm / "llvm-strip", "--strip-unneeded", "-o", reproduced, source_binary])
                require(digest(reproduced) == native[n]["sha256"], "Packaged JNI does not match independently reproduced NDK stripping")
                elf = command([llvm / "llvm-readelf", "--program-headers", "--dynamic", "--dyn-syms", reproduced])
                symbols = ["Java_com_narada_connection_NativeAgeBridge_" + name for name in ("info", "validIdentity", "encrypt", "decrypt")] if lib == "age" else ["Java_com_narada_connection_NativeOlmBridge_call"]
                require(all(symbol in elf for symbol in symbols), "Mandatory JNI exports missing")
                require("GNU_RELRO" in elf and re.search(r"FLAGS[^\n]*\b(?:BIND_NOW|NOW)\b", elf), "Native hardening flags missing")
                stacks = [line for line in elf.splitlines() if "GNU_STACK" in line]
                require(len(stacks) == 1 and "RWE" not in stacks[0] and re.search(r"\bRW\b", stacks[0]), "Executable or unknown native stack flags")
                (output() / f"native-{lib}-{abi}-elf.txt").write_text(elf, encoding="utf-8")
                native[n].update(built_input_sha256=original["sha256"], ndk_strip_tool_sha256=digest(llvm / "llvm-strip"), independently_reproduced_ndk_strip_matches=True, mandatory_jni_exports=symbols)
                # AAPT may transparently gunzip assets. Both representations must
                # preserve exactly the original notices; never remove for size.
                source = ROOT / f"apps/android/app/src/main/assets/native-{lib}-notices.txt.gz"
                plain = gzip.decompress(source.read_bytes())
                asset = f"assets/native-{lib}-notices.txt"
                if asset in names:
                    included = z.read(asset)
                else:
                    included = gzip.decompress(z.read(asset + ".gz"))
                require(included == plain, "Native notices changed or missing")
        target = dest / f"NARADA-{release_label()}-android-{abi}.apk"
        shutil.copy2(apk, target)
        reports.append({**file_row(target), "abi": abi, "native_libraries": native, "signer_sha256": signer[0].lower()})
        (output() / f"apk-{abi}-signature.txt").write_text(signature, encoding="utf-8")
        (output() / f"apk-{abi}-badging.txt").write_text(badging, encoding="utf-8")
        if abi == "x86_64": shutil.copy2(apk, emu / "app-x86_64.apk")
    require(seen == set(POLICY["android_abis"]) and len(signers) == 1, "Incomplete ABI set or inconsistent signer")
    tests = sorted((ROOT / "apps/android/app/build/outputs/apk/androidTest/manualTest").rglob("*.apk"))
    require(len(tests) == 1, "Missing matching manual-test instrumentation APK")
    signature = command([tools / "apksigner", "verify", "--print-certs", tests[0]])
    test_signer = re.findall(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", signature)
    require(len(test_signer) == 1 and test_signer[0].lower() in signers, "App and instrumentation signers differ")
    shutil.copy2(tests[0], emu / "instrumentation.apk")
    write_json(emu / "inputs.json", {**context(), "application_id": POLICY["android_application_id"], "files": [file_row(emu / "app-x86_64.apk"), file_row(emu / "instrumentation.apk")]})
    report = {**context(), "application_id": POLICY["android_application_id"], "version_name": POLICY["android_version_name"],
              "version_code": POLICY["android_version_code"], "signing": POLICY["signing"]["android"],
              "apk_budget_bytes_each": POLICY["android_apk_max_bytes"], "files": reports,
              "minified": True, "resource_shrunk": True, "universal_apk": False,
              "page_alignment": "Static ELF/ZIP check only; 16 KiB runtime not executed", "unqualified": POLICY["not_qualified"]}
    mapping = ROOT / "apps/android/app/build/outputs/mapping/manualTest/mapping.txt"
    require(mapping.is_file() and mapping.stat().st_size > 0, "Missing R8 mapping: minification not evidenced")
    report["r8_mapping_sha256"] = digest(mapping)
    write_json(dest / "android-build-report.json", report)
    shutil.copy2(ROOT / "docs/github/manual-testing.md", dest / "ANDROID-MANUAL-TEST.md")
    write_checksums(dest, "ANDROID-SHA256SUMS.txt")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=["android", "windows", "frontend"])
    choice = parser.parse_args().platform
    if choice == "android": android()
    elif choice == "windows": windows()
    else: print(json.dumps(frontend(), indent=2))
