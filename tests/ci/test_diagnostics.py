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
