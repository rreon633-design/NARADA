#!/usr/bin/env python3
"""Portable, fresh GitHub evidence. Never invokes historical campaign writers."""
from __future__ import annotations
import argparse
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SAFE_NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,79}\Z")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")


def output():
    job = os.environ.get("NARADA_CI_JOB", "")
    require(SAFE_NAME.fullmatch(job), "Set a safe NARADA_CI_JOB label")
    return ROOT / ".cache/ci-evidence" / job


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode("utf-8").strip()


def inputs():
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
    result = {}
    for name in filter(None, names):
        p = ROOT / name
        require(p.is_file() and not p.is_symlink(), f"Missing/symlink tracked input: {name}")
        require(not name.startswith((".ssh/", ".cache/", ".historical-runtime/")), "Private/generated input tracked")
        result[name] = digest(p)
    require(len(result) > 100, "Incomplete source checkout")
    return result


def begin():
    dest = output()
    dest.mkdir(parents=True, exist_ok=False)
    commit = git("rev-parse", "HEAD")
    require(commit == os.environ.get("GITHUB_SHA", commit), "Checkout differs from workflow commit")
    write_json(dest / "source-inputs.json", inputs())
    write_json(dest / "context.json", {
        "job": os.environ["NARADA_CI_JOB"], "commit": commit,
        "ref": os.environ.get("GITHUB_REF"), "run_id": os.environ.get("GITHUB_RUN_ID"),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "source_inputs_sha256": digest(dest / "source-inputs.json"),
        "campaign": "fresh GitHub CI; not a historical phase campaign",
        "started_unix": time.time(),
    })


def run(label, command, cwd="."):
    require(SAFE_NAME.fullmatch(label), "Unsafe command label")
    dest = output()
    require((dest / "context.json").is_file(), "Begin the fresh campaign first")
    command = command[1:] if command and command[0] == "--" else command
    require(bool(command), "Missing command")
    log = dest / "commands" / (label + ".log")
    receipt = log.with_suffix(".json")
    log.parent.mkdir(exist_ok=True)
    require(not log.exists() and not receipt.exists(), "Never overwrite a recorded command")
    working = (ROOT / cwd).resolve()
    require(working.is_relative_to(ROOT), "Command working directory must be in checkout")
    start = time.monotonic()
    code = 127
    with log.open("x", encoding="utf-8", newline="\n") as stream:
        try:
            child = subprocess.Popen(command, cwd=working, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
            for line in child.stdout:
                stream.write(line)
                stream.flush()
                print(line, end="", flush=True)
            code = child.wait()
        finally:
            write_json(receipt, {"label": label, "command": command, "cwd": cwd,
                                "exit_code": code, "duration_seconds": round(time.monotonic() - start, 3),
                                "log_sha256": digest(log)})
    return code


def junit_stats(paths):
    require(bool(paths), "No fresh JUnit XML; absence is not success")
    result = {"tests": 0, "passed": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in paths:
        tree = ET.parse(path)
        cases = list(tree.getroot().iter("testcase"))
        # A corrupt/misleading suite header must not hide failures or a missing run.
        for suite in tree.getroot().iter("testsuite"):
            for key in ("failures", "errors"):
                require(int(suite.attrib.get(key, 0)) == 0, f"{path}: nonzero {key}")
        for case in cases:
            result["tests"] += 1
            key = "failures" if case.find("failure") is not None else "errors" if case.find("error") is not None else "skipped" if case.find("skipped") is not None else "passed"
            result[key] += 1
    require(result["tests"] > 0, "JUnit XML contains zero executed test cases")
    return result


def record_junit(label, patterns, minimum):
    require(SAFE_NAME.fullmatch(label), "Unsafe suite label")
    files = sorted({str(Path(p).resolve()) for pattern in patterns for p in glob.glob(str(ROOT / pattern), recursive=True)})
    stats = junit_stats(files)
    require(stats["failures"] == stats["errors"] == stats["skipped"] == 0, f"Incomplete/failed suite: {stats}")
    require(stats["passed"] >= minimum, f"Expected at least {minimum} executed cases, found {stats}")
    write_json(output() / "suites" / (label + ".json"), {"label": label, **stats, "files": {str(Path(p).relative_to(ROOT)): digest(p) for p in files}})
    print(json.dumps({"label": label, **stats}))


def browser_redirect():
    # Legacy specs have fixed screenshot paths. Redirect only an ABSENT directory
    # in a fresh CI checkout; never replace or mutate the historical workspace.
    require(os.environ.get("CI") == "true", "Screenshot redirect is CI-only")
    alias = ROOT / "artifacts/desktop-5-9"
    require(not alias.exists() and not alias.is_symlink(), "Historical path already exists; refusing to overwrite it")
    dest = output() / "browser-screenshots"
    dest.mkdir()
    alias.parent.mkdir(exist_ok=True)
    alias.symlink_to(dest, target_is_directory=True)


def finish():
    dest = output()
    context = json.loads((dest / "context.json").read_text(encoding="utf-8"))
    original = json.loads((dest / "source-inputs.json").read_text(encoding="utf-8"))
    require(inputs() == original, "Tracked source changed during validation")
    records = [json.loads(p.read_text(encoding="utf-8")) for p in sorted((dest / "commands").glob("*.json"))]
    require(records and all(r["exit_code"] == 0 for r in records), "Missing or unsuccessful command evidence")
    suites = [json.loads(p.read_text(encoding="utf-8")) for p in sorted((dest / "suites").glob("*.json"))]
    result = {**context, "outcome": "passed", "finished_unix": time.time(),
              "command_records": len(records), "junit_suites": suites,
              "scope": "Development build/test evidence only. Not independent review, physical-device or production-release approval."}
    write_json(dest / "job-status.json", result)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(f"## {context['job']}\nFresh commit: `{context['commit']}`. {len(records)} successful recorded commands.\n\n")
            for suite in suites:
                stream.write(f"- {suite['label']}: {suite['passed']} passed; {suite['skipped']} skipped.\n")
            stream.write("\nThis is development evidence, not a production qualification.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="action", required=True)
    for name in ("begin", "finish", "browser-redirect"):
        subs.add_parser(name)
    p = subs.add_parser("run")
    p.add_argument("--cwd", default=".")
    p.add_argument("label")
    p.add_argument("command", nargs=argparse.REMAINDER)
    p = subs.add_parser("junit")
    p.add_argument("--minimum", type=int, required=True)
    p.add_argument("label")
    p.add_argument("patterns", nargs="+")
    args = parser.parse_args()
    if args.action == "begin": begin()
    elif args.action == "finish": finish()
    elif args.action == "browser-redirect": browser_redirect()
    elif args.action == "run": return run(args.label, args.command, args.cwd)
    elif args.action == "junit": record_junit(args.label, args.patterns, args.minimum)
    return 0


if __name__ == "__main__":
    sys.exit(main())
