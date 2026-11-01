import io
import os
from pathlib import Path
import sys
import unittest
from unittest import mock
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts/ci'))
import diagnostics

class DiagnosticsTests(unittest.TestCase):
    def test_annotation_commands_are_escaped(self):
        self.assertEqual('a%25%0A::error::x%0D', diagnostics.escaped('a%\n::error::x\r'))

    def test_token_values_are_redacted(self):
        with mock.patch.dict(os.environ, {'GH_TOKEN': 'synthetic-fixture-token'}):
            self.assertNotIn('synthetic-fixture-token', diagnostics.escaped('error synthetic-fixture-token'))
        self.assertEqual('[REDACTED]', diagnostics.escaped('ghp_' + 'x' * 40))

    def test_public_annotation_fits_github_limit(self):
        text = diagnostics.excerpt(('error: synthetic failure\n' * 1000).encode())
        self.assertLessEqual(len(text), 3200)
        self.assertIn('synthetic failure', text)

    def test_ci_vault_fixture_is_exactly_32_bytes(self):
        import re
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/build-test-release.yml').read_text()
        keys = re.findall(r"NARADA_DEV_VAULT_KEY: '([0-9a-f]+)'", workflow)
        self.assertEqual(len(keys), 1)
        self.assertEqual(len(bytes.fromhex(keys[0])), 32)

    def test_java_stack_does_not_hide_lint_cause(self):
        raw = ('build.gradle.kts:31: Error: synthetic [OldTargetApi]\n' + '\tat gradle.synthetic.Frame.method(File.kt:12)\n' * 200).encode()
        self.assertIn('[OldTargetApi]', diagnostics.excerpt(raw))

    def test_node_selection_is_explicit_and_has_live_and_junit_reporters(self):
        import node_tests
        cmd = node_tests.command(Path('synthetic-report.xml'))
        files = [v for v in cmd if v.endswith('.test.js')]
        self.assertEqual(5, len(files))
        self.assertNotIn('desktop-ui/tests', cmd)
        self.assertIn('--test-reporter=tap', cmd)
        self.assertIn('--test-reporter=junit', cmd)

    def test_excerpt_keeps_error_context(self):
        raw = b'2026-11-01T00:00:00Z compiling\n2026-11-01T00:00:01Z error[E001]: synthetic failure\n  source.rs:7\n'
        text = diagnostics.excerpt(raw)
        self.assertIn('error[E001]', text)
        self.assertIn('source.rs:7', text)
        self.assertNotIn('2026-11-01', text)

    def test_redirect_never_forwards_repository_token(self):
        error = urllib.error.HTTPError('https://api.github.com/test', 302, 'Redirect', {'Location': 'https://example.invalid/signed-log'}, io.BytesIO())
        opener = mock.Mock()
        opener.open.side_effect = error
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b'synthetic log'
        with mock.patch.dict(os.environ, {'GH_TOKEN': 'synthetic-fixture-token'}), mock.patch.object(diagnostics.urllib.request, 'build_opener', return_value=opener), mock.patch.object(diagnostics.urllib.request, 'urlopen', return_value=response) as fetch:
            self.assertEqual(b'synthetic log', diagnostics.request('/actions/jobs/1/logs'))
            self.assertIsNone(fetch.call_args.args[0].get_header('Authorization'))

    def test_plain_http_log_redirect_is_rejected(self):
        error = urllib.error.HTTPError('https://api.github.com/test', 302, 'Redirect', {'Location': 'http://example.invalid/log'}, io.BytesIO())
        opener = mock.Mock()
        opener.open.side_effect = error
        with mock.patch.dict(os.environ, {'GH_TOKEN': 'synthetic-fixture-token'}), mock.patch.object(diagnostics.urllib.request, 'build_opener', return_value=opener), mock.patch.object(diagnostics.urllib.request, 'urlopen') as fetch:
            with self.assertRaises(RuntimeError): diagnostics.request('/actions/jobs/1/logs')
            fetch.assert_not_called()

if __name__ == '__main__': unittest.main(verbosity=2)
