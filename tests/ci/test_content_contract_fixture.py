"""Unit-check the negative-peer fixture; these are NOT real network executions."""
import importlib.util
from pathlib import Path
import unittest
from unittest import mock

path = Path(__file__).resolve().parents[2] / 'tests/process/content_offline.py'
spec = importlib.util.spec_from_file_location('content_process_fixture', path)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

class ContentFixtureContractTests(unittest.TestCase):
    def exercise(self, changes=None):
        case = fixture.ContentOfflineTests('test_independent_mtls_peer_has_no_content_without_scoped_consent')
        case.root = 'synthetic-root'
        case.a = mock.Mock()
        case.a.snapshot.return_value = {'endpoint': '127.0.0.1:1'}
        raw = mock.Mock()
        raw.code = {'fingerprint': 'synthetic-public-fixture'}
        responses = [{'ok': False, 'error': {'code': code}, 'data': None} for code in ('PERMISSION_DENIED', 'ACCESS_DENIED', 'NO_PEER_ROUTE')]
        if changes: responses[1].update(changes)
        raw.post.side_effect = responses
        with mock.patch.object(case, 'active_raw', return_value=raw), mock.patch.object(fixture.time, 'sleep'):
            case.test_independent_mtls_peer_has_no_content_without_scoped_consent()

    def test_exact_denials_match_the_two_authorization_layers(self):
        self.exercise()

    def test_success_cannot_pass_the_consent_negative_case(self):
        with self.assertRaises(AssertionError): self.exercise({'ok': True})

    def test_denial_cannot_carry_content(self):
        with self.assertRaises(AssertionError): self.exercise({'data': {'leaked': 'synthetic'}})

    def test_wrong_denial_contract_still_fails(self):
        with self.assertRaises(AssertionError): self.exercise({'error': {'code': 'PERMISSION_DENIED'}})

if __name__ == '__main__': unittest.main(verbosity=2)
