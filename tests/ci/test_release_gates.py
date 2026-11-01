import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
import publish

spec = importlib.util.spec_from_file_location('prepare_github', ROOT / 'scripts/prepare_github.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.sha = 'a' * 40

    def make_status(self, name, **changes):
        folder = self.base / name
        folder.mkdir()
        data = b'{"synthetic-fixture":"source-digest"}\n'
        (folder / 'source-inputs.json').write_bytes(data)
        row = {'job': name, 'outcome': 'passed', 'commit': self.sha, 'run_id': '10', 'run_attempt': '2', 'command_records': 1, 'source_inputs_sha256': hashlib.sha256(data).hexdigest()}
        row.update(changes)
        (folder / 'job-status.json').write_text(json.dumps(row))
        return folder

    def all_status(self):
        for name in sorted(publish.EXPECTED_JOBS): self.make_status(name)

    def verify(self):
        return publish.verify_evidence(self.base, self.sha, '10', '2')

    def test_all_required_jobs_same_attempt(self):
        self.all_status()
        self.assertEqual(publish.EXPECTED_JOBS, set(self.verify()))

    def test_missing_job_cannot_publish(self):
        for name in sorted(publish.EXPECTED_JOBS - {'emulator-api29'}): self.make_status(name)
        with self.assertRaises(RuntimeError): self.verify()

    def test_failed_or_wrong_commit_or_mixed_attempt_cannot_publish(self):
        for change in ({'outcome': 'failed'}, {'commit': 'b' * 40}, {'run_attempt': '1'}, {'command_records': 0}):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as tmp:
                original = self.base
                self.base = Path(tmp)
                for name in sorted(publish.EXPECTED_JOBS): self.make_status(name, **(change if name == 'windows' else {}))
                with self.assertRaises(RuntimeError): self.verify()
                self.base = original

    def test_source_evidence_tampering_is_rejected(self):
        self.all_status()
        (self.base / 'android/source-inputs.json').write_bytes(b'changed')
        with self.assertRaises(RuntimeError): self.verify()

    def test_checksum_file_binds_complete_asset_set(self):
        data = self.base / 'fixture.apk'
        data.write_bytes(b'INERT TEST FILE; NOT AN APK')
        checks = self.base / 'SHA256SUMS.txt'
        checks.write_text(hashlib.sha256(data.read_bytes()).hexdigest() + '  fixture.apk\n')
        publish.verify_checksum_file(checks)
        (self.base / 'extra.bin').write_bytes(b'unlisted')
        with self.assertRaises(RuntimeError): publish.verify_checksum_file(checks)

    def test_checksum_traversal_and_mismatch_rejected(self):
        checks = self.base / 'SHA256SUMS.txt'
        for name in ('../outside', '/absolute', 'sub/file'):
            checks.write_text('0' * 64 + '  ' + name + '\n')
            with self.assertRaises(RuntimeError): publish.verify_checksum_file(checks)
        (self.base / 'fixture.apk').write_bytes(b'x')
        checks.write_text('0' * 64 + '  fixture.apk\n')
        with self.assertRaises(RuntimeError): publish.verify_checksum_file(checks)

    def release_environment(self):
        return {'GITHUB_REPOSITORY': publish.REPOSITORY, 'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': self.sha, 'GITHUB_RUN_ID': '10', 'GITHUB_RUN_ATTEMPT': '2'}

    def test_failed_evidence_cannot_create_an_initial_tag(self):
        with mock.patch.dict('os.environ', self.release_environment(), clear=True), mock.patch.object(publish, 'ROOT', self.base), mock.patch.object(publish, 'api', return_value=None) as api:
            with self.assertRaises(RuntimeError): publish.main()
            self.assertEqual(1, api.call_count)
            self.assertEqual(('git/ref/tags/v0.0.1',), api.call_args.args)

    def test_an_existing_tag_at_another_commit_is_never_moved(self):
        ref = {'object': {'type': 'commit', 'sha': 'b' * 40}}
        with mock.patch.dict('os.environ', self.release_environment(), clear=True), mock.patch.object(publish, 'api', side_effect=[ref, None, []]) as api:
            publish.main()
            self.assertEqual(3, api.call_count)
            self.assertTrue(all(len(call.args) == 1 for call in api.call_args_list))

    def test_published_initial_release_is_not_overwritten(self):
        ref = {'object': {'type': 'commit', 'sha': self.sha}}
        with mock.patch.dict('os.environ', self.release_environment(), clear=True), mock.patch.object(publish, 'api', side_effect=[ref, {'draft': False}]) as api:
            publish.main()
            self.assertTrue(all(len(call.args) == 1 for call in api.call_args_list))

    def test_existing_draft_is_found_even_when_by_tag_returns_404(self):
        draft = {'id': 7, 'tag_name': 'v0.0.1', 'draft': True}
        with mock.patch.object(publish, 'api', side_effect=[None, [draft]]) as api:
            self.assertEqual(draft, publish.existing_release('v0.0.1'))
            self.assertEqual(2, api.call_count)
            self.assertTrue(all(len(call.args) == 1 for call in api.call_args_list))

    def test_release_listing_limit_fails_closed(self):
        rows = [{'tag_name': 'other', 'draft': True}] * 100
        with mock.patch.object(publish, 'api', side_effect=[None] + [rows] * 10):
            with self.assertRaises(RuntimeError): publish.existing_release('v0.0.1')

    def test_pull_requests_cannot_publish_or_create_refs(self):
        env = self.release_environment()
        env['GITHUB_EVENT_NAME'] = 'pull_request'
        with mock.patch.dict('os.environ', env, clear=True), mock.patch.object(publish, 'api') as api:
            with self.assertRaises(RuntimeError): publish.main()
            api.assert_not_called()

    def test_source_export_excludes_credentials_history_and_build_outputs(self):
        for path in ('.ssh/id_ed25519', '.historical-runtime/private.zip', '.cache/profile.bin', 'artifacts/phase-4/report.json', 'apps/android/app/build/output.apk', 'apps/android/signing.jks', '.env.local', 'apps/desktop-shell/src-tauri/binaries/narada-agent-x86_64.exe', '.github/workflows/phase-1.yml'):
            self.assertFalse(prepare.allowed(path), path)
        for path in ('Cargo.lock', 'apps/android/gradle/wrapper/gradle-wrapper.jar', 'apps/android/app/src/main/AndroidManifest.xml', prepare.WORKFLOW):
            self.assertTrue(prepare.allowed(path), path)

    def test_private_key_blocks_are_detected_without_printing_them(self):
        fixture = b'-----BEGIN OPENSSH PRIVATE KEY-----\n' + b'A' * 64 + b'\n-----END OPENSSH PRIVATE KEY-----'
        self.assertIsNotNone(prepare.PRIVATE_KEY.search(fixture))
        self.assertIsNone(prepare.PRIVATE_KEY.search(b'ssh-ed25519 SYNTHETIC-PUBLIC-KEY'))


if __name__ == '__main__': unittest.main(verbosity=2)
