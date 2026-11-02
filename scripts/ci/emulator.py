#!/usr/bin/env python3
"""Execute the instrumentation APK against the EXACT minified download candidate."""
import json
import os
from pathlib import Path
import re
import subprocess
from evidence import ROOT, digest, output, require, write_json


def adb(*args, timeout=180):
    p = subprocess.run(["adb", *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       text=True, encoding="utf-8", errors="replace", timeout=timeout)
    print(p.stdout, end="", flush=True)
    require(p.returncode == 0, f"ADB command failed: {args[0]}")
    return p.stdout


def parse_result(text, minimum=13):
    failure_summary = text.rsplit("INSTRUMENTATION_RESULT: stream=", 1)[-1].strip()[-2200:]
    require(not any(s in text for s in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "INSTRUMENTATION_ABORTED")), "Native instrumentation failed: " + failure_summary)
    status = [int(n) for n in re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", text, flags=re.MULTILINE)]
    require(status and all(n in (0, 1) for n in status), "Missing progress, skipped/assumed or failed native cases")
    counts = re.findall(r"^OK \((\d+) tests?\)\s*$", text, flags=re.MULTILINE)
    require(len(counts) == 1 and int(counts[0]) >= minimum, "Missing/incomplete native test execution")
    require("INSTRUMENTATION_CODE: -1" in text, "Missing successful instrumentation completion")
    require(status.count(0) == status.count(1) == int(counts[0]), "Native case progress and summary disagree")
    return int(counts[0])


def main():
    base = ROOT / ".cache/ci-emulator-inputs"
    manifest = json.loads((base / "inputs.json").read_text(encoding="utf-8"))
    require(manifest["commit"] == os.environ["GITHUB_SHA"], "APK and emulator checkout commits differ")
    require(str(manifest["run_id"]) == os.environ["GITHUB_RUN_ID"] and str(manifest["run_attempt"]) == os.environ["GITHUB_RUN_ATTEMPT"], "APK came from another CI attempt")
    require({r["name"] for r in manifest["files"]} == {"app-x86_64.apk", "instrumentation.apk"}, "Unexpected emulator inputs")
    for row in manifest["files"]:
        require((base / row["name"]).stat().st_size == row["bytes"] and digest(base / row["name"]) == row["sha256"], "APK changed in transit")
    package = manifest["application_id"]
    require(package == "com.narada.mobile.dev.ci", "Never target the original development app")
    abi = adb("shell", "getprop", "ro.product.cpu.abi").strip()
    api = adb("shell", "getprop", "ro.build.version.sdk").strip()
    page_size = adb("shell", "getconf", "PAGE_SIZE").strip()
    require(abi == "x86_64" and api == os.environ["NARADA_EMULATOR_API"], "Unexpected emulator image")
    # Fresh CI emulator only. No clear/uninstall command touches a user's profile.
    adb("install", "--no-incremental", str(base / "app-x86_64.apk"))
    adb("install", "--no-incremental", "-t", str(base / "instrumentation.apk"))
    # Exercise native launch separately from instrumentation's test activity setup.
    adb("shell", "am", "start", "-W", "-n", package + "/com.narada.android.MainActivity")
    text = adb("shell", "am", "instrument", "-w", "-r", package + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=900)
    (output() / "instrumentation.txt").write_text(text, encoding="utf-8")
    try:
        count = parse_result(text)
    except Exception:
        crash = adb("logcat", "-b", "crash", "-d")
        (output() / "crash-logcat.txt").write_text(crash, encoding="utf-8")
        raise
    write_json(output() / "native-execution.json", {
        "commit": manifest["commit"], "api": int(api), "abi": abi,
        "page_size": int(page_size), "executed": count, "passed": count,
        "failed": 0, "app_sha256": digest(base / "app-x86_64.apk"),
        "scope": "Hosted emulator; minified APK JNI, AndroidKeyStore and existing instrumentation cases. Not a physical-device, LAN or whole-state-rollback qualification.",
    })


if __name__ == "__main__": main()
