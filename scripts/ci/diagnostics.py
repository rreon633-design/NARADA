#!/usr/bin/env python3
"""Read this public repository's failed CI job logs with a scoped workflow token.
Only bounded, escaped diagnostic excerpts become check annotations; no token or
full environment is printed. This does not change any job/release outcome.
"""
import json
import os
import re
import urllib.request
import urllib.error
import urllib.parse

REPO = 'rreon633-design/NARADA'
BASE = 'https://api.github.com/repos/' + REPO
ANSI = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')
TOKEN = re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{40,})\b')


def request(path):
    token = os.environ['GH_TOKEN']
    req = urllib.request.Request(BASE + path, headers={'Authorization': 'Bearer ' + token,
         'User-Agent': 'NARADA-CI-diagnostics', 'Accept': 'application/vnd.github+json'})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl): return None
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=60) as response:
            return response.read(8 * 1024 * 1024)
    except urllib.error.HTTPError as error:
        if error.code not in (301, 302, 303, 307, 308): raise
        location = error.headers.get('Location', '')
        parsed = urllib.parse.urlparse(location)
        if parsed.scheme != 'https' or parsed.username or parsed.password:
            raise RuntimeError('Unsafe diagnostic log redirect')
        # GitHub returns a short-lived signed log URL. Do NOT forward the
        # repository token to blob storage or any other redirected host.
        public = urllib.request.Request(location, headers={'User-Agent': 'NARADA-CI-diagnostics'})
        with urllib.request.urlopen(public, timeout=60) as response:
            return response.read(8 * 1024 * 1024)


def get(path): return json.loads(request(path))


def escaped(text):
    text = TOKEN.sub('[REDACTED]', text)
    text = text.replace(os.environ.get('GH_TOKEN', '\x00'), '[REDACTED]')
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def excerpt(raw):
    text = ANSI.sub('', raw.decode('utf-8', errors='replace'))
    lines = [re.sub(r'^\d{4}-\d\d-\d\dT\S+\s+', '', line) for line in text.splitlines()]
    selected = set()
    for index, line in enumerate(lines):
        if re.search(r'error\[|error:|panicked at|^failures:|Exception|Traceback|Could not|Failed to|failed to|UnsupportedClassVersion|assertion .+failed|test result: FAILED', line, re.I):
            selected.update(range(max(0, index - 3), min(len(lines), index + 12)))
    if not selected: selected.update(range(max(0, len(lines) - 70), len(lines)))
    return '\n'.join(lines[i] for i in sorted(selected))[-3200:]


def main():
    if os.environ.get('GITHUB_REPOSITORY') != REPO: raise RuntimeError('Owner repository only')
    raw = os.environ.get('DIAGNOSTIC_RUN_ID', '')
    if raw:
        if not raw.isdecimal(): raise RuntimeError('Invalid workflow run ID')
        run = get('/actions/runs/' + raw)
    else:
        rows = get('/actions/workflows/build-test-release.yml/runs?status=completed&branch=main&per_page=1')['workflow_runs']
        if not rows:
            print('No completed build campaign to diagnose.'); return
        run = rows[0]
    print('Diagnostic source: ' + run['html_url'] + ', commit ' + run['head_sha'])
    jobs = get('/actions/runs/' + str(run['id']) + '/jobs?per_page=100')['jobs']
    for job in jobs:
        if job.get('conclusion') != 'failure': continue
        try:
            text = excerpt(request('/actions/jobs/' + str(job['id']) + '/logs'))
            # Label this as a diagnostic of an already-failed run, not a fresh
            # validation failure or any sort of successful rerun.
            message = f"Previous failed run {run['id']}, job {job['id']}, commit {run['head_sha']}\n" + text
            print('::warning title=Failed-run diagnostics::' + escaped(message), flush=True)
        except Exception as error:
            print('::warning title=Diagnostic fetch unavailable::' + escaped(type(error).__name__ + ': ' + str(error)))


if __name__ == '__main__': main()
