#!/usr/bin/env python3
"""Synthetic release-tool tests. Keys/receipts here do not qualify any release."""
import hashlib, importlib.util, json, subprocess, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
update=module('offline_update',ROOT/'scripts/release/verify_update.py')
gate=module('release_gate',ROOT/'scripts/release/check_release.py')
class ReleaseTools(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.key=self.root/'synthetic-private.pem';self.public=self.root/'synthetic-public.pem';self.manifest=self.root/'manifest.json';self.signature=self.root/'manifest.sig';self.artifact=self.root/'synthetic-not-executable.exe';self.artifact.write_bytes(b'INERT_SYNTHETIC_UPDATE_FIXTURE')
        subprocess.run(['openssl','genpkey','-algorithm','ED25519','-out',str(self.key)],check=True,capture_output=True);subprocess.run(['openssl','pkey','-in',str(self.key),'-pubout','-out',str(self.public)],check=True,capture_output=True)
        self.trusted=hashlib.sha256(self.public.read_bytes()).hexdigest();self.doc={'application':'NARADA','version':'0.8.0','platform':'windows-x86_64','artifact':self.artifact.name,'size':str(self.artifact.stat().st_size),'sha256':hashlib.sha256(self.artifact.read_bytes()).hexdigest(),'metadata_schema':6,'channel':'qualified-desktop'}
    def tearDown(self):self.temp.cleanup()
    def sign(self):
        self.manifest.write_text(json.dumps(self.doc));subprocess.run(['openssl','pkeyutl','-sign','-inkey',str(self.key),'-rawin','-in',str(self.manifest),'-out',str(self.signature)],check=True,capture_output=True)
    def verify(self,installed='0.7.0',trusted=None):return update.verify(self.manifest,self.signature,self.public,self.artifact,trusted or self.trusted,installed)
    def test_valid_synthetic_signature_verifies_but_never_executes(self):self.sign();self.assertEqual(self.verify()['executed'],False)
    def test_unsigned_or_tampered_manifest_is_refused(self):
        self.sign();self.manifest.write_bytes(self.manifest.read_bytes()+b' ')
        with self.assertRaises(ValueError):self.verify()
    def test_wrong_trust_anchor_is_refused(self):
        self.sign()
        with self.assertRaises(ValueError):self.verify(trusted='0'*64)
    def test_wrong_artifact_digest_is_refused(self):
        self.sign();self.artifact.write_bytes(b'ATTACKER_REPLACEMENT')
        with self.assertRaises(ValueError):self.verify()
    def test_downgrade_and_replayed_release_are_refused(self):
        self.sign()
        for installed in ['0.8.0','0.9.0']:
            with self.assertRaises(ValueError):self.verify(installed=installed)
    def test_unknown_fields_and_wrong_schema_are_refused_even_if_signed(self):
        self.doc['execute']='not permitted';self.sign()
        with self.assertRaises(ValueError):self.verify()
        self.doc.pop('execute');self.doc['metadata_schema']=5;self.sign()
        with self.assertRaises(ValueError):self.verify()
    def test_channel_and_platform_are_bound(self):
        for field,value in [('channel','preview'),('platform','android')]:
            previous=self.doc[field];self.doc[field]=value;self.sign()
            with self.assertRaises(ValueError):self.verify()
            self.doc[field]=previous
    def test_signed_duplicate_fields_are_refused(self):
        self.sign();raw=self.manifest.read_text();self.manifest.write_text(raw[:-1]+',"application":"NARADA"}')
        subprocess.run(['openssl','pkeyutl','-sign','-inkey',str(self.key),'-rawin','-in',str(self.manifest),'-out',str(self.signature)],check=True,capture_output=True)
        with self.assertRaises(ValueError):self.verify()
    def test_oversized_manifest_is_refused_before_openssl(self):
        self.sign();self.manifest.write_bytes(b' '*16385)
        with self.assertRaises(ValueError):self.verify()
    def test_pending_real_qualification_is_not_marked_approved(self):
        document=json.loads((ROOT/'release/qualification.json').read_text());document['approvals']={};self.assertGreaterEqual(len(gate.check(document,ROOT)),8)
if __name__=='__main__':unittest.main(verbosity=2)
