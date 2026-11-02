import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
import windows_bundle
import artifacts


class WindowsBundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'checkout'
        self.root.mkdir()
        self.evidence = self.root / '.cache/ci-evidence/windows'
        self.folder = self.root / windows_bundle.BUNDLE
        self.context = {'commit': 'a' * 40, 'run_id': '10', 'run_attempt': '1', 'source_inputs_sha256': 'b' * 64, 'channel': 'inert-fixture'}

    def put(self, name='old.exe', data=b'INERT TEST FILE; NOT AN EXECUTABLE'):
        self.folder.mkdir(parents=True, exist_ok=True)
        p = self.folder / name
        p.write_bytes(data)
        return p

    def prepare(self):
        return windows_bundle.prepare_bundle(self.root, self.evidence, self.context)

    def test_two_restored_installers_are_preserved_not_selected_or_deleted(self):
        self.put('old-version.exe', b'INERT OLD CANDIDATE')
        self.put('previous-attempt.exe', b'INERT DIFFERENT CANDIDATE')
        original = windows_bundle.inventory(self.root, self.folder)
        report = self.prepare()
        self.assertEqual(original, report['prior_files'])
        self.assertEqual([], list(self.folder.iterdir()))
        self.assertEqual(original, windows_bundle.inventory(self.root, self.root / windows_bundle.DISPLACED))
        self.assertTrue(report['prior_bytes_preserved'])
        artifacts.verify_windows_bundle_preparation(report, self.context)
        self.assertTrue((self.evidence / 'windows-bundle-before.json').is_file())

    def test_first_build_without_restored_directory_is_valid(self):
        report = self.prepare()
        self.assertEqual({}, report['prior_files'])
        self.assertIsNone(report['displaced_directory'])
        self.assertEqual([], list(self.folder.iterdir()))
        artifacts.verify_windows_bundle_preparation(report, self.context)

    def test_repeated_preparation_never_overwrites_evidence(self):
        self.put(); self.prepare()
        path = self.evidence / 'windows-bundle-preparation.json'
        before = path.read_bytes()
        with self.assertRaises(RuntimeError): self.prepare()
        self.assertEqual(before, path.read_bytes())

    def test_existing_displaced_outputs_fail_closed(self):
        self.put()
        saved = self.root / windows_bundle.DISPLACED
        saved.mkdir(parents=True)
        (saved / 'preserve.txt').write_bytes(b'INERT SAVED OUTPUT')
        with self.assertRaises(RuntimeError): self.prepare()
        self.assertTrue((self.folder / 'old.exe').is_file())
        self.assertEqual(b'INERT SAVED OUTPUT', (saved / 'preserve.txt').read_bytes())

    def test_symlinked_target_ancestor_is_refused(self):
        outside = Path(self.temp.name) / 'outside'
        outside.mkdir()
        (self.root / 'target').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(RuntimeError): self.prepare()
        self.assertEqual([], list(outside.iterdir()))

    def test_symlink_inside_restored_output_is_refused(self):
        self.put()
        outside = self.root / 'outside.txt'
        outside.write_bytes(b'INERT OUTSIDE INPUT')
        (self.folder / 'linked.exe').symlink_to(outside)
        with self.assertRaises(RuntimeError): self.prepare()
        self.assertTrue((self.folder / 'old.exe').is_file())
        self.assertFalse((self.root / windows_bundle.DISPLACED).exists())

    def test_inventory_count_and_byte_limits_are_not_bypassed(self):
        self.put('one.exe', b'INERT ONE'); self.put('two.exe', b'INERT TWO')
        for key, value in (('MAX_FILES', 1), ('MAX_BYTES', 4)):
            with self.subTest(key=key), mock.patch.object(windows_bundle, key, value):
                with self.assertRaises(RuntimeError): self.prepare()
        self.assertEqual(2, len(list(self.folder.iterdir())))

    def test_missing_false_or_wrong_source_proof_cannot_collect(self):
        valid = self.prepare()
        for change in ({'schema': 2}, {'fresh_output_empty': False}, {'prior_bytes_preserved': False}, {'source_directory': 'elsewhere'}, {'context': {**self.context, 'commit': 'c' * 40}}):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                artifacts.verify_windows_bundle_preparation({**valid, **change}, self.context)

    def test_workflow_isolates_outputs_before_build_and_keeps_single_installer_gate(self):
        workflow = (ROOT / '.github/workflows/build-test-release.yml').read_text()
        prepare = workflow.index('run isolate-windows-bundles -- python scripts/ci/windows_bundle.py')
        build = workflow.index('run native-build -- node scripts/native-build.mjs')
        collect = workflow.index('run collect-windows -- python scripts/ci/artifacts.py windows')
        self.assertLess(prepare, build)
        self.assertLess(build, collect)
        collector = (ROOT / 'scripts/ci/artifacts.py').read_text()
        self.assertIn('require(len(installers) == 1', collector)
        self.assertIn('verify_windows_bundle_preparation(preparation, context())', collector)


if __name__ == '__main__': unittest.main(verbosity=2)
