#!/usr/bin/env python3
"""Fail-closed release evidence gate. File/hash presence is NOT an independent audit."""
import argparse, hashlib, json, re, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from source_hashes import capture
REQUIRED={'windows_msvc_tauri_installer','dpapi_named_pipe_acl','filesystem_share_modes_power_loss','physical_lan_sleep_wake_matrix','screen_reader_keyboard_high_dpi','crypto_integration_independent_review','dependency_and_license_review','pilot_and_product_go_no_go'}
def check(document,root):
    blockers=[]
    if document.get('application')!='NARADA':blockers.append('Wrong application')
    if document.get('release_channel')!='qualified-desktop':blockers.append('Channel is not qualified-desktop')
    import tomllib
    version=tomllib.loads((root/'Cargo.toml').read_text())['workspace']['package']['version']
    if document.get('source_version')!=version:blockers.append('Evidence version differs from source version')
    for name in sorted(REQUIRED):
        row=document.get('approvals',{}).get(name,{})
        if row.get('status')!='approved' or not isinstance(row.get('reviewer'),str) or not row['reviewer'].strip():blockers.append(name+': no named approval');continue
        relative=row.get('evidence_path');expected=row.get('sha256','')
        if not isinstance(relative,str) or not isinstance(expected,str) or not re.fullmatch('[0-9a-f]{64}',expected):blockers.append(name+': evidence/hash missing');continue
        path=(root/relative).resolve()
        base=(root/'qualification-evidence').absolute()
        lexical=Path(relative)
        link=any((root.joinpath(*lexical.parts[:i])).is_symlink() for i in range(1,len(lexical.parts)+1))
        if lexical.is_absolute() or '..' in lexical.parts or link or not path.is_relative_to(base) or not path.is_file():blockers.append(name+': evidence must be a local review artifact in qualification-evidence');continue
        if hashlib.sha256(path.read_bytes()).hexdigest()!=expected:blockers.append(name+': evidence hash mismatch')
    report=root/'artifacts/desktop-5-9/validation-summary.json'
    if not report.exists():blockers.append('Consolidated host validation has not completed')
    else:
        result=json.loads(report.read_text())
        if result.get('status')!='passed' or result.get('version')!=version:blockers.append('Current host validation is not fully passing')
        tested=root/'artifacts/desktop-5-9/tested-source-hashes.json'
        if not tested.exists():blockers.append('Tested source binding is missing')
        else:
            recorded=json.loads(tested.read_text())
            actual=capture(root)
            if not isinstance(recorded,dict) or not recorded:blockers.append('Tested source binding is empty/invalid')
            elif recorded!=actual:
                for name in sorted(set(recorded)|set(actual)):
                    if recorded.get(name)!=actual.get(name):blockers.append('Source differs from tested source: '+name)
    return blockers
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--expect-blocked',action='store_true');args=parser.parse_args()
    blockers=check(json.loads((ROOT/'release/qualification.json').read_text()),ROOT)
    print(json.dumps({'application':'NARADA','release_allowed':not blockers,'blockers':blockers,'note':'Hash-bound documents require genuine authorized reviewers; this tool cannot certify their substantive truth.'},indent=2))
    raise SystemExit((0 if blockers else 1) if args.expect_blocked else (2 if blockers else 0))
