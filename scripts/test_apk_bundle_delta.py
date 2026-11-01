#!/usr/bin/env python3
"""Packaging-only standard-library delta checks; NOT part of the 796 app checks."""
import bz2, hashlib, json, lzma, tempfile, unittest
from pathlib import Path
from apk_bundle_delta import patch, restore_bytes

def number(n):return (abs(n)|((1<<63) if n<0 else 0)).to_bytes(8,'little')
def make(size,controls,diff=b'',extra=b''):
    c=bz2.compress(b''.join(number(v) for triple in controls for v in triple));d=bz2.compress(diff);e=bz2.compress(extra)
    return b'BSDIFF40'+number(len(c))+number(len(d))+number(size)+c+d+e
class DeltaTests(unittest.TestCase):
    def test_common_copy_and_literal(self):self.assertEqual(b'hello world',patch(b'hello',make(11,[(5,6,0)],b'\0'*5,b' world'),11))
    def test_wrapping_delta_bytes(self):self.assertEqual(b'\0B',patch(b'\xffA',make(2,[(2,0,0)],b'\1\1'),2))
    def test_signed_seek(self):self.assertEqual(b'xx',patch(b'x',make(2,[(1,0,-1),(1,0,0)],b'\0\0'),2))
    def test_bounds_truncation_and_unused_bytes(self):
        for delta,size in [(make(1,[(2,0,0)],b'\0\0'),1),(make(2,[(2,0,0)],b'\0'),2),(make(1,[(1,0,0)],b'\0\0'),1),(b'BSDIFF40',1)]:
            with self.assertRaises(AssertionError):patch(b'a',delta,size)
    def test_container_hash_binding_and_corruption(self):
        old=lzma.compress(b'hello',preset=6);new=lzma.compress(b'hello world',preset=6);d=make(11,[(5,6,0)],b'\0'*5,b' world');sha=lambda b:hashlib.sha256(b).hexdigest()
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder);base=p/'base.xz';delta=p/'change.bsdiff';meta=p/'metadata.json';base.write_bytes(old);delta.write_bytes(d)
            m={'schema':1,'format':'bsdiff40-exact-tar-xz6','base':{'bytes':len(old),'sha256':sha(old),'tar_bytes':5,'tar_sha256':sha(b'hello')},'target':{'bytes':len(new),'sha256':sha(new),'tar_bytes':11,'tar_sha256':sha(b'hello world')},'delta':{'bytes':len(d),'sha256':sha(d)}}
            meta.write_text(json.dumps(m));self.assertEqual(new,restore_bytes(meta,base,delta)[0]);self.assertEqual(b'hello world',restore_bytes(meta,base,delta,True)[0])
            base.write_bytes(old[:-1]+b'x')
            with self.assertRaises(AssertionError):restore_bytes(meta,base,delta)
            base.write_bytes(old);delta.write_bytes(d[:-1]+b'x')
            with self.assertRaises(AssertionError):restore_bytes(meta,base,delta)
if __name__=='__main__':unittest.main(verbosity=2)
