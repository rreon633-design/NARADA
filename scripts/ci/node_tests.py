#!/usr/bin/env python3
"""Portable explicit Node test discovery, with live diagnostics and fresh JUnit."""
import sys
from evidence import ROOT, output, require, run, record_junit


def command(report):
    files = sorted(p for p in (ROOT / 'desktop-ui/tests').rglob('*.test.*')
                   if p.suffix in {'.js', '.mjs', '.cjs'} and p.is_file())
    require(bool(files), 'No frontend test files discovered')
    require(all(not p.is_symlink() for p in files), 'Symlink test input refused')
    return ['node', '--test', '--test-reporter=tap', '--test-reporter=junit',
            '--test-reporter-destination=stdout', '--test-reporter-destination=' + str(report),
            *[p.relative_to(ROOT).as_posix() for p in files]]


def main():
    report = output() / 'node-tests.xml'
    require(not report.exists(), 'Never overwrite frontend test evidence')
    code = run('node-tests', command(report))
    if code != 0: return code
    record_junit('node-tests', [report.relative_to(ROOT).as_posix()], 53)
    return 0


if __name__ == '__main__': sys.exit(main())
