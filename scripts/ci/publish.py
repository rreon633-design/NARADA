#!/usr/bin/env python3
"""Publish same-run green candidates; create the initial tag once, never move refs/overwrite releases."""
from __future__ import annotations
import json
import datetime
import os
from pathlib import Path
import re
import shutil
import urllib.error
import urllib.parse
import urllib.request
from artifacts import POLICY, release_label
from evidence import ROOT, digest, require, write_json

EXPECTED_JOBS = {"tooling", "desktop-linux", "windows", "android", "emulator-api29", "emulator-api35"}
REPOSITORY = "rreon633-design/NARADA"


def api(path, method="GET", data=None, *, missing=False):
    token = os.environ.get("GH_TOKEN", "")
    require(bool(token), "GitHub's ephemeral workflow token is required; never use the SSH deploy key here")
    url = "https://api.github.com/repos/" + REPOSITORY + "/" + path
    body = None if data is None else json.dumps(data).encode("utf-8")
    request = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "NARADA-development-release",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if missing and error.code == 404: return None
        raise RuntimeError(f"GitHub API {method} failed with HTTP {error.code}; existing tags/releases were not modified") from None


def verify_evidence(base, sha, run, attempt):
    statuses = {}
    for path in base.rglob("job-status.json"):
        record = json.loads(path.read_text(encoding="utf-8-sig"))
        name = record["job"]
        require(name in EXPECTED_JOBS and name not in statuses, "Unexpected/duplicate CI status")
        require(record["outcome"] == "passed" and record["commit"] == sha and str(record["run_id"]) == run and str(record["run_attempt"]) == attempt, "Evidence is not successful from this exact CI attempt and commit")
        require(record["command_records"] > 0, "No command evidence")
        source = path.parent / "source-inputs.json"
        require(source.is_file() and digest(source) == record["source_inputs_sha256"], "Source evidence damaged in transit")
        statuses[name] = record
    require(set(statuses) == EXPECTED_JOBS, "A required successful CI job is missing")
    return statuses


def verify_checksum_file(path):
    seen = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        require(re.fullmatch(r"[0-9a-f]{64}  [A-Za-z0-9_.-]+", line), "Malformed checksum record")
        sha, name = line.split("  ", 1)
        require(name not in seen, "Duplicate checksum entry")
        seen.add(name)
        target = path.parent / name
        require(target.is_file() and not target.is_symlink() and digest(target) == sha, "Release artifact checksum mismatch")
    require(bool(seen), "Empty checksum file")
    require(seen == {p.name for p in path.parent.iterdir() if p.is_file() and p != path}, "Unlisted/extra package asset")


def main():
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY and os.environ.get("GITHUB_EVENT_NAME") == "push" and (os.environ.get("GITHUB_REF", "").startswith("refs/tags/v") or os.environ.get("GITHUB_REF") == "refs/heads/main"), "Release is restricted to an owner-repository tag/main push")
    sha, run, attempt = os.environ["GITHUB_SHA"], os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
    initial = os.environ["GITHUB_REF"] == "refs/heads/main"
    tag = POLICY["initial_github_tag"] if initial else release_label()
    require(re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", tag), "Invalid version tag")
    existing_ref = api("git/ref/tags/" + tag, missing=True)
    if initial and existing_ref is not None:
        # Later commits must never replace v0.0.1. A rerun of the exact tagged
        # commit may retry publication only when no release/draft already exists.
        existing = existing_ref["object"]
        for _ in range(4):
            if existing["type"] == "commit": break
            require(existing["type"] == "tag", "Unsupported existing tag object")
            existing = api("git/tags/" + existing["sha"])["object"]
        old_release = api("releases/tags/" + tag, missing=True)
        if existing.get("sha") != sha or (old_release is not None and not old_release["draft"]):
            print(f"Initial tag/release {tag} already exists. Leaving it untouched; this push still has fresh CI artifacts.")
            return
    base = ROOT / ".cache/release-inputs"
    statuses = verify_evidence(base / "evidence", sha, run, attempt)
    packages = base / "packages"
    android_dir = packages / f"android-per-abi-manual-test-attempt-{attempt}"
    windows_dir = packages / f"windows-x64-manual-test-attempt-{attempt}"
    verify_checksum_file(android_dir / "ANDROID-SHA256SUMS.txt")
    verify_checksum_file(windows_dir / "WINDOWS-SHA256SUMS.txt")
    android = json.loads((android_dir / "android-build-report.json").read_text())
    windows = json.loads((windows_dir / "windows-build-report.json").read_text())
    for name, report in (("android", android), ("windows", windows)):
        require(report["commit"] == sha and str(report["run_id"]) == run and str(report["run_attempt"]) == attempt, "Package comes from another run/commit")
        require(report["source_inputs_sha256"] == statuses[name]["source_inputs_sha256"], "Package and job source inputs differ")
        folder = android_dir if name == "android" else windows_dir
        for row in report["files"]:
            p = folder / row["name"]
            require(p.parent == folder and p.is_file() and p.stat().st_size == row["bytes"] and digest(p) == row["sha256"], "Package report does not describe actual downloadable bytes")
    require({r["abi"] for r in android["files"]} == set(POLICY["android_abis"]), "Missing per-ABI candidate")
    x64 = next(r for r in android["files"] if r["abi"] == "x86_64")
    native = {}
    for p in (base / "evidence").rglob("native-execution.json"):
        r = json.loads(p.read_text())
        require(r["api"] in (29, 35) and r["api"] not in native, "Unexpected/duplicate native execution")
        require(r["commit"] == sha and r["app_sha256"] == x64["sha256"] and r["executed"] == r["passed"] and r["passed"] >= 13 and r["failed"] == 0, "Emulator did not execute the exact released minified APK")
        native[r["api"]] = r
    require(set(native) == {29, 35}, "Missing required native emulator execution")
    smoke_files = list((base / "evidence").rglob("windows-native-smoke.json"))
    require(len(smoke_files) == 1, "Missing Windows native installer/bootstrap evidence")
    smoke = json.loads(smoke_files[0].read_text(encoding="utf-8-sig"))
    native_hashes = {r["name"]: r["sha256"] for r in windows["native_binaries"]}
    require(smoke["commit"] == sha and smoke["installer_exit_code"] == 0 and smoke["application_stayed_alive_seconds"] >= 15, "Windows bootstrap failed")
    require(smoke["installed_application_sha256"] == native_hashes["narada-desktop.exe"] and smoke["installed_agent_sha256"] == native_hashes["narada-agent.exe"], "Installed Windows binaries differ from release build")

    # An initial main-branch campaign creates the requested tag only after ALL
    # checks above succeed. No API call can move or force-update an existing ref.
    require(api("releases/tags/" + tag, missing=True) is None, "Release already exists; refusing to overwrite it (including a previous draft)")
    if initial and existing_ref is None:
        require(api("git/ref/heads/main")["object"]["sha"] == sha, "Main advanced; do not tag an obsolete campaign")
        annotated = api("git/tags", "POST", {
            "tag": tag, "message": f"NARADA {tag} development manual-test candidate; all required checks passed in run {run}, attempt {attempt}.",
            "object": sha, "type": "commit",
            "tagger": {"name": "NARADA CI", "email": "narada-ci@users.noreply.github.com", "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")},
        })
        existing_ref = api("git/refs", "POST", {"ref": "refs/tags/" + tag, "sha": annotated["sha"]})
    require(existing_ref is not None, "Expected the pushed tag to exist")
    ref = existing_ref["object"]
    for _ in range(4):
        if ref["type"] == "commit": break
        require(ref["type"] == "tag", "Unsupported tag object")
        ref = api("git/tags/" + ref["sha"])["object"]
    require(ref["type"] == "commit" and ref["sha"] == sha, "Remote tag does not identify the tested commit")

    summary = {"tag": tag, "commit": sha, "run_id": run, "run_attempt": attempt,
               "required_jobs": {k: {"outcome": v["outcome"], "command_records": v["command_records"], "source_inputs_sha256": v["source_inputs_sha256"]} for k, v in statuses.items()},
               "native_emulator_execution": native, "windows_native_bootstrap": smoke,
               "qualification": "Development manual testing only; not production/security approval",
               "unqualified": POLICY["not_qualified"]}
    publish_dir = ROOT / ".cache/ci-publish"
    publish_dir.mkdir(parents=True, exist_ok=False)
    write_json(publish_dir / "CI-RELEASE-EVIDENCE.json", summary)
    # Branch-run candidates are named dev-<commit>; rename COPIES for the first
    # tagged release. Binary bytes, signatures and tested hashes never change.
    for platform, folder, report in (("android", android_dir, android), ("windows", windows_dir, windows)):
        for row in report["files"]:
            old_name = row["name"]
            suffix = "android-" + row["abi"] + ".apk" if platform == "android" else "windows-x64-setup.exe"
            new_name = f"NARADA-{tag}-{suffix}"
            require(not (publish_dir / new_name).exists(), "Conflicting release asset names")
            shutil.copy2(folder / old_name, publish_dir / new_name)
            row["source_candidate_artifact_name"] = old_name
            row["name"] = new_name
        write_json(publish_dir / f"{platform}-build-report.json", report)
        shutil.copy2(folder / f"{platform.upper()}-MANUAL-TEST.md", publish_dir / f"{platform.upper()}-MANUAL-TEST.md")
    files = sorted(p for p in publish_dir.iterdir() if p.is_file())
    checksums = publish_dir / "SHA256SUMS.txt"
    checksums.write_text("".join(f"{digest(p)}  {p.name}\n" for p in files), encoding="utf-8")
    files.append(checksums)
    notes = [f"# NARADA {tag} — development manual-test prerelease", "", f"Source commit: `{sha}`. Fresh GitHub run: https://github.com/{REPOSITORY}/actions/runs/{run}", "", "## Downloads", "", "- **Windows x64:** download the `windows-x64-setup.exe` asset. Unsigned; requires the system WebView2 runtime. No Electron/Chromium bundle.", "- **Android:** download only the APK matching your device ABI (`arm64-v8a` for most modern phones; do not install all four). Android 10/API 29 or newer.", "", "### Measured download sizes", ""]
    for row in windows["files"] + android["files"]:
        notes.append(f"- `{row['name']}`: {row['bytes']:,} bytes ({row['bytes'] / 1048576:.2f} MiB).")
    notes += ["", "## Important boundaries", "", "- Android is a **non-debuggable, minified test build**, signed with a temporary CI debug certificate. Package: `com.narada.mobile.dev.ci`, launcher: **NARADA (Test)**. It does not replace the earlier `.dev` app. Use disposable test data; subsequent runs can use a different signer.", "- This first GitHub tag is `v0.0.1`; existing Android/desktop shell application version `0.7.0`, Android versionCode `7`, and Rust workspace `0.7.1` are intentionally retained. No data/schema downgrade or production upgrade is claimed.", "- Native instrumentation ran against the **same minified x86_64 APK** on API 29 and 35 hosted emulators. Windows checks include native unit tests and a silent installer/bootstrap smoke. Neither replaces physical-device/LAN/security qualification.", "- Checksums and build reports are attached. Reports list remaining gates. There is no production signing, independent security/legal approval, validated in-place upgrade, or measured performance guarantee on your hardware.", "- Read `ANDROID-MANUAL-TEST.md` / `WINDOWS-MANUAL-TEST.md` before installing. Never remove valued data to resolve signer or storage continuity errors."]
    body = "\n".join(notes) + "\n"
    # Create as DRAFT, upload/verify everything, and only then make it visible.
    created = api("releases", "POST", {"tag_name": tag, "target_commitish": sha,
                  "name": f"NARADA {tag} — manual test", "body": body, "draft": True, "prerelease": True})
    release_id = created["id"]
    upload_url = created["upload_url"].split("{")[0]
    expected = f"https://uploads.github.com/repos/{REPOSITORY}/releases/{release_id}/assets"
    require(upload_url == expected, "Unexpected asset upload host/path")
    for path in files:
        url = upload_url + "?" + urllib.parse.urlencode({"name": path.name})
        request = urllib.request.Request(url, data=path.read_bytes(), method="POST", headers={
            "Authorization": "Bearer " + os.environ["GH_TOKEN"], "User-Agent": "NARADA-development-release",
            "Content-Type": "application/octet-stream", "Accept": "application/vnd.github+json",
        })
        with urllib.request.urlopen(request, timeout=180) as response:
            result = json.load(response)
        require(result["name"] == path.name and result["size"] == path.stat().st_size and result["state"] == "uploaded", "Incomplete asset upload; draft remains unpublished")
        if result.get("digest"):
            require(result["digest"] == "sha256:" + digest(path), "Server asset digest mismatch; draft remains unpublished")
        print(f"Uploaded {path.name}: {path.stat().st_size} bytes")
    listed = api(f"releases/{release_id}/assets?per_page=100")
    require({p.name for p in files} == {r["name"] for r in listed} and len(listed) == len(files), "Incomplete draft asset set")
    published = api(f"releases/{release_id}", "PATCH", {"draft": False, "prerelease": True})
    require(published["draft"] is False and published["prerelease"] is True, "Release publication not confirmed")
    print("Published development prerelease: " + published["html_url"])


if __name__ == "__main__": main()
