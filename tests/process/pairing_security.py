#!/usr/bin/env python3
"""Real separate-agent and independent OpenSSL peer tests. Loopback only.
Test identities live only in disposable .narada-data fixtures. No production secrets.
"""
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import sqlite3
import ssl
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / 'target/debug/narada-agent'
TEST_KEY = '6b' * 32  # Explicit, disposable development fixture; never a production key.
NO_CONTENT = {'chat': False, 'attachment': False, 'roots': []}
HELLO = {'version': 1, 'capabilities': ['connection.health', 'connection.permissions']}

def wait_for(fn, timeout=12):
    until = time.monotonic() + timeout
    last = None
    while time.monotonic() < until:
        try:
            value = fn()
            if value:
                return value
        except (OSError, KeyError, RuntimeError) as e:
            last = type(e).__name__
        time.sleep(.05)
    raise AssertionError(f'Condition did not become true; last error class: {last}')

class Agent:
    def __init__(self, path, key=TEST_KEY):
        self.path, self.key, self.process = Path(path), key, None
        self.path.mkdir(parents=True, exist_ok=True)
        self.logfile = self.path / 'agent-test.log'
        self.start()
    def start(self):
        self.log = self.logfile.open('ab')
        self.process = subprocess.Popen([str(BINARY), '--data-dir', str(self.path)],
            env={**os.environ, 'NARADA_DEV_VAULT_KEY': self.key}, stdout=self.log, stderr=subprocess.STDOUT)
        wait_for(lambda: self.raw('get_snapshot')['ok'])
    def raw(self, command, payload=None):
        message = {'version': 1, 'id': str(uuid.uuid4()), 'command': {'type': command}}
        if payload is not None:
            message['command']['payload'] = payload
        with socket.socket(socket.AF_UNIX) as s:
            s.settimeout(8)
            s.connect(str(self.path / 'agent.sock'))
            s.sendall(json.dumps(message).encode() + b'\n')
            data = b''
            while not data.endswith(b'\n'):
                chunk = s.recv(65536)
                if not chunk:
                    raise RuntimeError('IPC closed before a complete response')
                data += chunk
            result = json.loads(data)
        assert result['id'] == message['id']
        return result
    def call(self, command, payload=None):
        result = self.raw(command, payload)
        if not result['ok']:
            raise RuntimeError(result['error']['code'])
        return result['data']
    def snapshot(self):
        return self.call('get_snapshot')['connections']
    def enable(self):
        return self.call('configure_connections', {'enabled': True, 'bind_ip': '127.0.0.1', 'api_port': 0, 'discovery': False})
    def invite(self):
        return self.call('start_pairing')
    def stop(self, kill=False):
        if self.process and self.process.poll() is None:
            if kill:
                self.process.kill()
            else:
                self.process.terminate()
            self.process.wait(timeout=10)
        self.log.close()
    def restart(self, kill=False):
        self.stop(kill)
        self.start()

class RawPeer:
    """Independent TLS/HTTP reference implementation, with disposable OpenSSL keys.
    CERT_NONE below is ONLY for this test client's negative cases; it subsequently
    checks the exact QR certificate fingerprint before sending any pairing secret.
    Production Rust clients additionally validate WebPKI, dates, SAN and signatures.
    """
    def __init__(self, path):
        self.path = Path(path)
        self.path.mkdir()
        self.cert, self.key = self.path/'cert.pem', self.path/'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256',
            '-nodes', '-days', '1', '-keyout', str(self.key), '-out', str(self.cert),
            '-subj', '/CN=NARADA disposable reference', '-addext', 'subjectAltName=DNS:narada.local',
            '-addext', 'keyUsage=critical,digitalSignature', '-addext', 'extendedKeyUsage=serverAuth,clientAuth',
            '-addext', 'basicConstraints=critical,CA:FALSE'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        self.der = ssl.PEM_cert_to_DER_cert(self.cert.read_text())
        self.fingerprint = hashlib.sha256(self.der).hexdigest()
        self.attempt = str(uuid.uuid4())
        self.connection = None
    def connect(self, endpoint, pin, mtls=False, tls12=False):
        if self.connection:
            self.connection.close()
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_2 if tls12 else ssl.TLSVersion.TLSv1_3
        if mtls:
            ctx.load_cert_chain(self.cert, self.key)
        host, port = endpoint.split(':')
        conn = http.client.HTTPSConnection(host, int(port), context=ctx, timeout=5)
        conn.connect()
        assert hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest() == pin, 'Test client pin mismatch'
        self.connection = conn
        return self
    def post(self, route, value):
        self.connection.request('POST', route, body=json.dumps(value), headers={'Content-Type': 'application/json'})
        response = self.connection.getresponse()
        result = json.loads(response.read())
        return result
    def claim(self, code, **overrides):
        self.code = code
        message = {'hello': HELLO, 'invitation_id': code['invitation_id'], 'attempt_id': self.attempt,
            'token': code['token'], 'certificate': base64.b64encode(self.der).decode(),
            'name': 'Independent reference', 'platform': 'linux', **overrides}
        return self.connect(code['endpoint'], code['fingerprint']).post('/pair/v1/claim', message)
    def proof(self, ack=False):
        return self.post('/pair/v1/ack' if ack else '/pair/v1/prove', {'hello': HELLO,
            'invitation_id': self.code['invitation_id'], 'attempt_id': self.attempt})
    def close(self):
        if self.connection:
            self.connection.close()

class PairingTests(unittest.TestCase):
    def setUp(self):
        base = ROOT/'.narada-data/p2'
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.root = Path(self.temp.name)
        self.a = Agent(self.root/'a')
        self.b = Agent(self.root/'b')
        self.raws = []
    def tearDown(self):
        for p in self.raws:
            p.close()
        self.a.stop()
        self.b.stop()
        self.temp.cleanup()
    def raw_peer(self):
        p = RawPeer(self.root/f'raw-{len(self.raws)}')
        self.raws.append(p)
        return p
    def pair(self):
        self.a.enable()
        self.b.enable()
        invite = self.a.invite()
        self.b.call('join_pairing', {'code': invite['code']})
        pending = wait_for(lambda: (self.a.snapshot()['invitation'] or {}).get('pending'))
        wait_for(lambda: self.a.snapshot()['invitation']['pending']['proof_verified'])
        self.assertEqual(self.a.snapshot()['peers'], [])
        self.a.call('approve_pairing', {'attempt_id': pending['attempt_id'], 'permissions': NO_CONTENT})
        wait_for(lambda: any(p['state'] == 'active' and p['connection_state'] == 'online' for p in self.b.snapshot()['peers']))
        wait_for(lambda: any(p['connection_state'] == 'online' for p in self.a.snapshot()['peers']))
        return self.a.snapshot()['peers'][0], self.b.snapshot()['peers'][0]
    def test_opt_in_identity_survives_restart_and_is_not_plaintext(self):
        snap = self.a.snapshot()
        self.assertEqual(snap['identity']['state'], 'ready')
        self.assertFalse(snap['listening'])
        self.assertFalse(snap['config']['enabled'])
        self.assertIn('Development test vault', snap['identity']['protection'])
        blob = (self.a.path/'transport.identity').read_bytes()
        self.assertNotIn(b'"key"', blob)
        self.assertNotIn(b'BEGIN PRIVATE KEY', blob)
        self.a.restart()
        self.assertEqual(snap['identity'], self.a.snapshot()['identity'])
        self.assertEqual(blob, (self.a.path/'transport.identity').read_bytes())
    def test_two_agents_require_approval_then_exchange_mtls_health(self):
        host, client = self.pair()
        self.assertEqual(host['permissions'], NO_CONTENT)
        self.assertEqual(client['permissions'], NO_CONTENT)
        self.assertEqual(self.a.snapshot()['peers'][0]['connection_state'], 'online')
        self.assertIsNone(self.a.snapshot()['invitation'])
        self.assertEqual(self.b.snapshot()['join']['state'], 'paired')
    def test_certificate_submission_does_not_prove_private_key_or_allow_approval(self):
        self.a.enable()
        code = json.loads(self.a.invite()['code'])
        raw = self.raw_peer()
        claimed = raw.claim(code)
        self.assertTrue(claimed['ok'], claimed)
        pending = self.a.snapshot()['invitation']['pending']
        self.assertFalse(pending['proof_verified'])
        denied = self.a.raw('approve_pairing', {'attempt_id': raw.attempt, 'permissions': NO_CONTENT})
        self.assertEqual(denied['error']['code'], 'PROOF_REQUIRED')
        raw.connect(f"127.0.0.1:{claimed['data']['api_port']}", code['fingerprint'], mtls=True)
        self.assertEqual(raw.proof()['data']['state'], 'awaiting_owner')
        self.assertEqual(raw.proof(ack=True)['error']['code'], 'OWNER_APPROVAL_REQUIRED')
        self.assertEqual(self.a.snapshot()['peers'], [])
    def test_revocation_closes_active_session_and_survives_restart(self):
        host, _ = self.pair()
        self.a.call('revoke_peer', {'peer_id': host['peer_id'], 'expected_revision': host['grant_revision']})
        snap = self.a.snapshot()
        self.assertEqual(snap['peers'][0]['state'], 'revoked')
        self.assertGreater(snap['peers'][0]['session_epoch'], host['session_epoch'])
        self.assertEqual(snap['active_sessions'], 0)
        self.a.restart()
        self.assertEqual(self.a.snapshot()['peers'][0]['state'], 'revoked')
        self.b.call('reconnect_peer', {'peer_id': self.b.snapshot()['peers'][0]['peer_id']})
        wait_for(lambda: self.b.snapshot()['peers'][0]['connection_state'] == 'blocked')

    def _raw_active(self):
        self.a.enable()
        code = json.loads(self.a.invite()['code'])
        raw = self.raw_peer()
        reply = raw.claim(code)
        self.assertTrue(reply['ok'])
        raw.connect(f"127.0.0.1:{reply['data']['api_port']}", code['fingerprint'], mtls=True)
        self.assertEqual(raw.proof()['data']['state'], 'awaiting_owner')
        self.a.call('approve_pairing', {'attempt_id': raw.attempt, 'permissions': NO_CONTENT})
        self.assertEqual(raw.proof(ack=True)['data']['state'], 'active')
        self.assertTrue(raw.post('/api/v1/hello', HELLO)['ok'])
        return raw, self.a.snapshot()['peers'][0]

    def test_missing_protected_key_blocks_network_without_regeneration(self):
        self.a.stop()
        key_file = self.a.path/'transport.identity'
        key_file.unlink()
        self.a.start()
        identity = self.a.snapshot()['identity']
        self.assertEqual(identity['error']['code'], 'IDENTITY_KEY_MISSING')
        self.assertFalse(key_file.exists())
        rejected = self.a.raw('configure_connections', {'enabled': True, 'bind_ip': '127.0.0.1', 'api_port': 0, 'discovery': False})
        self.assertEqual(rejected['error']['code'], 'IDENTITY_KEY_MISSING')
        self.assertFalse(self.a.snapshot()['listening'])

    def test_wrong_wrapping_key_preserves_original_protected_blob(self):
        original = (self.a.path/'transport.identity').read_bytes()
        self.a.stop()
        self.a.key = '2c'*32
        self.a.start()
        self.assertEqual(self.a.snapshot()['identity']['error']['code'], 'KEYSTORE_LOCKED')
        self.assertEqual(original, (self.a.path/'transport.identity').read_bytes())
        self.a.stop()
        self.a.key = TEST_KEY
        self.a.start()
        self.assertEqual(self.a.snapshot()['identity']['state'], 'ready')

    def test_corrupt_identity_is_not_reset(self):
        self.a.stop()
        broken = b'corrupt protected identity'
        (self.a.path/'transport.identity').write_bytes(broken)
        self.a.start()
        self.assertEqual(self.a.snapshot()['identity']['error']['code'], 'IDENTITY_CORRUPT')
        self.assertEqual((self.a.path/'transport.identity').read_bytes(), broken)

    def test_valid_but_different_saved_identity_is_not_silently_adopted(self):
        self.a.stop()
        swapped = (self.b.path/'transport.identity').read_bytes()
        (self.a.path/'transport.identity').write_bytes(swapped)
        self.a.start()
        self.assertEqual(self.a.snapshot()['identity']['error']['code'], 'IDENTITY_CHANGED')
        self.assertEqual((self.a.path/'transport.identity').read_bytes(), swapped)

    def test_invitation_token_is_one_time_and_wrong_tokens_do_not_claim_it(self):
        self.a.enable()
        code = json.loads(self.a.invite()['code'])
        first = self.raw_peer()
        result = first.claim(code, token='f'*64)
        self.assertEqual(result['error']['code'], 'INVITATION_INVALID')
        self.assertIsNone(self.a.snapshot()['invitation']['pending'])
        self.assertTrue(first.claim(code)['ok'])
        second = self.raw_peer()
        self.assertEqual(second.claim(code)['error']['code'], 'INVITATION_USED')
        self.assertEqual(first.claim(code)['error']['code'], 'INVITATION_USED')
        self.assertEqual(self.a.snapshot()['peers'], [])

    def test_unknown_certificate_and_no_client_certificate_are_denied(self):
        self.a.enable()
        snap = self.a.snapshot()
        raw = self.raw_peer()
        for mtls in [False, True]:
            with self.assertRaises((OSError, http.client.HTTPException)):
                raw.connect(snap['endpoint'], snap['identity']['fingerprint'], mtls=mtls).post('/api/v1/hello', HELLO)
        self.assertEqual(self.a.snapshot()['peers'], [])

    def test_claimed_but_unapproved_peer_cannot_use_normal_api(self):
        self.a.enable()
        code = json.loads(self.a.invite()['code'])
        raw = self.raw_peer()
        reply = raw.claim(code)
        raw.connect(f"127.0.0.1:{reply['data']['api_port']}", code['fingerprint'], mtls=True)
        self.assertEqual(raw.post('/api/v1/health', HELLO)['error']['code'], 'PAIRING_NOT_ACTIVE')
        self.assertEqual(self.a.snapshot()['peers'], [])

    def test_tls12_and_application_protocol_downgrades_are_rejected(self):
        self.a.enable()
        code = json.loads(self.a.invite()['code'])
        raw = self.raw_peer()
        with self.assertRaises(ssl.SSLError):
            raw.connect(code['endpoint'], code['fingerprint'], tls12=True)
        for version in [0, 2, 99]:
            result = raw.claim(code, hello={**HELLO, 'version': version})
            self.assertEqual(result['error']['code'], 'PROTOCOL_INCOMPATIBLE')
        self.assertIsNone(self.a.snapshot()['invitation']['pending'])

    def test_missing_capability_unknown_fields_and_oversized_claims_are_rejected(self):
        self.a.enable()
        code = json.loads(self.a.invite()['code'])
        raw = self.raw_peer()
        self.assertEqual(raw.claim(code, hello={'version': 1, 'capabilities': []})['error']['code'], 'CAPABILITIES_INCOMPATIBLE')
        self.assertEqual(raw.claim(code, owner_admin=True)['error']['code'], 'INVALID_HANDSHAKE')
        self.assertEqual(raw.claim(code, certificate='x'*18000)['error']['code'], 'BODY_TOO_LARGE')
        self.assertIsNone(self.a.snapshot()['invitation']['pending'])
        self.assertTrue(raw.claim(code)['ok'])

    def test_expired_self_signed_certificate_is_rejected(self):
        self.a.enable()
        code = json.loads(self.a.invite()['code'])
        raw = self.raw_peer()
        expired = raw.path/'expired.pem'
        subprocess.run(['openssl', 'x509', '-in', str(raw.cert), '-signkey', str(raw.key), '-days', '0', '-out', str(expired)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        raw.der = ssl.PEM_cert_to_DER_cert(expired.read_text())
        time.sleep(1.1)
        self.assertEqual(raw.claim(code)['error']['code'], 'INVALID_CERTIFICATE')

    def test_owner_and_content_routes_are_never_exposed_to_a_paired_peer(self):
        raw, _ = self._raw_active()
        for path in ['/rpc', '/api/v1/shutdown', '/api/v1/approve', '/api/v1/files', '/api/v1/chat', '/api/v1/attachments', '/api/v1/files?path=../../private']:
            # A rejected route may close an unread HTTP request body; each case
            # obtains a fresh authenticated channel, rather than assuming reuse.
            raw.connect(self.a.snapshot()['endpoint'], raw.code['fingerprint'], mtls=True)
            self.assertEqual(raw.post(path, {'type': 'shutdown'})['error']['code'], 'INVALID_HANDSHAKE' if path in ['/api/v1/files', '/api/v1/chat'] else 'NO_PEER_ROUTE')
        self.assertTrue(self.a.raw('get_snapshot')['ok'])
        raw.connect(self.a.snapshot()['endpoint'], raw.code['fingerprint'], mtls=True)
        self.assertEqual(raw.post('/api/v1/health', {**HELLO, 'version': 0})['error']['code'], 'PROTOCOL_INCOMPATIBLE')

    def test_permission_update_closes_existing_tls_session_and_changes_epochs(self):
        raw, peer = self._raw_active()
        self.a.call('update_peer_permissions', {'peer_id': peer['peer_id'], 'expected_revision': peer['grant_revision'], 'permissions': NO_CONTENT})
        newer = self.a.snapshot()['peers'][0]
        self.assertGreater(newer['session_epoch'], peer['session_epoch'])
        self.assertEqual(self.a.snapshot()['active_sessions'], 0)
        with self.assertRaises((OSError, http.client.HTTPException)):
            raw.post('/api/v1/health', HELLO)
        stale = self.a.raw('update_peer_permissions', {'peer_id': peer['peer_id'], 'expected_revision': peer['grant_revision'], 'permissions': NO_CONTENT})
        self.assertEqual(stale['error']['code'], 'REVISION_CONFLICT')

    def test_final_ack_is_idempotent_and_does_not_duplicate_trust(self):
        raw, peer = self._raw_active()
        self.assertEqual(raw.proof(ack=True)['data']['state'], 'active')
        self.assertEqual(raw.proof(ack=True)['data']['state'], 'active')
        self.assertEqual(len(self.a.snapshot()['peers']), 1)
        self.assertEqual(self.a.snapshot()['peers'][0]['grant_revision'], peer['grant_revision'])

    def test_identity_pin_is_preserved_when_manual_route_points_to_an_attacker(self):
        _, client = self.pair()
        attacker = Agent(self.root/'attacker')
        try:
            endpoint = attacker.enable()['endpoint']
            self.b.call('set_peer_endpoint', {'peer_id': client['peer_id'], 'endpoint': endpoint})
            wait_for(lambda: self.b.snapshot()['peers'][0]['connection_state'] == 'blocked')
            saved = self.b.snapshot()['peers'][0]
            self.assertEqual(saved['fingerprint'], client['fingerprint'])
            self.assertEqual(saved['last_error']['code'], 'IDENTITY_REJECTED')
            self.assertEqual(attacker.snapshot()['peers'], [])
        finally:
            attacker.stop()

    def test_reconnect_after_abrupt_host_restart_uses_original_identities(self):
        host, client = self.pair()
        host_identity = self.a.snapshot()['identity']
        client_identity = self.b.snapshot()['identity']
        self.a.restart(kill=True)
        self.b.call('reconnect_peer', {'peer_id': client['peer_id']})
        wait_for(lambda: self.b.snapshot()['peers'][0]['connection_state'] == 'online')
        self.assertEqual(self.a.snapshot()['identity'], host_identity)
        self.assertEqual(self.b.snapshot()['identity'], client_identity)
        self.assertEqual(self.a.snapshot()['peers'][0]['peer_id'], host['peer_id'])

    def test_unknown_public_and_metadata_routes_never_replace_a_saved_endpoint(self):
        _, peer = self.pair()
        for endpoint in ['example.com:443', '8.8.8.8:443', '169.254.169.254:80', '0.0.0.0:80', '224.0.0.251:5353', '127.0.0.1:0', 'https://u:p@192.168.1.2:80']:
            rejected = self.b.raw('set_peer_endpoint', {'peer_id': peer['peer_id'], 'endpoint': endpoint})
            self.assertFalse(rejected['ok'])
        self.assertEqual(self.b.snapshot()['peers'][0]['endpoint'], peer['endpoint'])

    def test_linux_preview_refuses_lan_and_loopback_discovery(self):
        for ip in ['0.0.0.0', '192.168.1.23', '8.8.8.8']:
            rejected = self.a.raw('configure_connections', {'enabled': True, 'bind_ip': ip, 'api_port': 0, 'discovery': False})
            self.assertEqual(rejected['error']['code'], 'PUBLIC_NETWORK_BLOCKED')
        rejected = self.a.raw('configure_connections', {'enabled': True, 'bind_ip': '127.0.0.1', 'api_port': 0, 'discovery': True})
        self.assertEqual(rejected['error']['code'], 'DISCOVERY_UNAVAILABLE')
        self.assertFalse(self.a.snapshot()['listening'])

    def test_no_invitation_secret_in_snapshots_diagnostics_database_or_logs(self):
        self.a.enable()
        invitation = self.a.invite()
        secret = json.loads(invitation['code'])['token']
        self.assertNotIn(secret, json.dumps(self.a.snapshot()))
        report = json.dumps(self.a.call('run_diagnostics'))
        self.assertNotIn(secret, report)
        self.assertNotIn(self.a.snapshot()['identity']['fingerprint'], report)
        self.assertNotIn('127.0.0.1', report)
        self.assertNotIn(secret.encode(), (self.a.path/'narada.db').read_bytes())
        self.assertNotIn(secret, self.a.logfile.read_text())

    def test_restart_cancels_unfinished_invitation_instead_of_restoring_secret(self):
        self.a.enable()
        code = self.a.invite()['code']
        self.a.restart()
        self.assertIsNone(self.a.snapshot()['invitation'])
        self.b.enable()
        self.b.call('join_pairing', {'code': code})
        wait_for(lambda: self.b.snapshot()['join']['state'] == 'failed')
        self.assertEqual(self.a.snapshot()['peers'], [])

    def test_cancel_join_prevents_late_owner_approval_from_creating_client_trust(self):
        self.a.enable()
        self.b.enable()
        self.b.call('join_pairing', {'code': self.a.invite()['code']})
        wait_for(lambda: (self.a.snapshot()['invitation']['pending'] or {}).get('proof_verified'))
        attempt = self.b.snapshot()['join']['attempt_id']
        self.b.call('cancel_join', {'attempt_id': attempt})
        self.a.call('approve_pairing', {'attempt_id': attempt, 'permissions': NO_CONTENT})
        time.sleep(.9)
        self.assertEqual(self.b.snapshot()['join']['state'], 'cancelled')
        self.assertEqual(self.b.snapshot()['peers'], [])
        self.assertEqual(self.a.snapshot()['peers'], [])

    def test_disable_network_closes_listener_but_preserves_protected_identity(self):
        raw, peer = self._raw_active()
        before = self.a.snapshot()
        self.a.call('configure_connections', {**before['config'], 'enabled': False})
        after = self.a.snapshot()
        self.assertFalse(after['listening'])
        self.assertEqual(after['active_sessions'], 0)
        self.assertEqual(before['identity'], after['identity'])
        self.assertEqual(after['peers'][0]['state'], 'active')
        with self.assertRaises((OSError, http.client.HTTPException)):
            raw.post('/api/v1/health', HELLO)
        host, port = before['endpoint'].split(':')
        with self.assertRaises(OSError):
            socket.create_connection((host, int(port)), timeout=1)

    @unittest.skipUnless(os.environ.get('NARADA_SLOW_TESTS') == '1', 'Opt in to real retry/backoff timing')
    def test_reconnect_backoff_eventually_stops_without_changing_trust(self):
        _, peer = self.pair()
        self.a.stop()
        self.b.call('reconnect_peer', {'peer_id': peer['peer_id']})
        wait_for(lambda: self.b.snapshot()['peers'][0]['connection_state'] == 'retry_stopped', timeout=45)
        saved = self.b.snapshot()['peers'][0]
        self.assertEqual(saved['last_error']['code'], 'RETRY_LIMIT')
        self.assertEqual(saved['fingerprint'], peer['fingerprint'])
        self.assertEqual(saved['state'], 'active')

    @unittest.skipUnless(os.environ.get('NARADA_SLOW_TESTS') == '1', 'Opt in to real 120-second server expiry')
    def test_real_invitation_deadline_closes_bootstrap_and_pending_mtls(self):
        self.a.enable()
        code = json.loads(self.a.invite()['code'])
        raw = self.raw_peer()
        reply = raw.claim(code)
        api = f"127.0.0.1:{reply['data']['api_port']}"
        wait_for(lambda: self.a.snapshot()['invitation'] is None, timeout=125)
        with self.assertRaises((OSError, http.client.HTTPException)):
            raw.connect(api, code['fingerprint'], mtls=True).post('/pair/v1/prove', {'hello': HELLO, 'invitation_id': code['invitation_id'], 'attempt_id': raw.attempt})
        def bootstrap_closed():
            host, port = code['endpoint'].split(':')
            try:
                with socket.create_connection((host, int(port)), timeout=.3):
                    return False
            except OSError:
                return True
        # Logical expiry is immediate; listener teardown runs on the 500 ms monitor.
        wait_for(bootstrap_closed, timeout=2)
        self.assertEqual(self.a.snapshot()['peers'], [])

    def test_disabling_joiner_closes_outbound_and_pending_connections(self):
        self.a.enable()
        self.b.enable()
        self.b.call('join_pairing', {'code': self.a.invite()['code']})
        wait_for(lambda: (self.a.snapshot()['invitation']['pending'] or {}).get('proof_verified'))
        self.assertGreater(self.a.snapshot()['active_sessions'], 0)
        self.b.call('configure_connections', {**self.b.snapshot()['config'], 'enabled': False})
        wait_for(lambda: self.a.snapshot()['active_sessions'] == 0)
        self.assertFalse(self.b.snapshot()['listening'])
        self.assertEqual(self.b.snapshot()['join']['state'], 'cancelled')
        self.assertEqual(self.a.snapshot()['peers'], [])

    def test_pending_acknowledgement_recovers_against_durable_host_commit(self):
        host, client = self.pair()
        self.b.stop()
        # Fault injection models a client crash after the host commit but before the
        # local activation write. No secret or peer certificate is substituted.
        with sqlite3.connect(self.b.path/'narada.db') as db:
            db.execute("UPDATE trusted_peers SET state='pending' WHERE peer_id=?", (client['peer_id'],))
        self.b.start()
        wait_for(lambda: self.b.snapshot()['peers'][0]['state'] == 'active' and self.b.snapshot()['peers'][0]['connection_state'] == 'online')
        self.assertEqual(len(self.a.snapshot()['peers']), 1)
        self.assertEqual(self.a.snapshot()['peers'][0]['grant_revision'], host['grant_revision'])
        self.assertEqual(self.b.snapshot()['peers'][0]['fingerprint'], client['fingerprint'])

    @unittest.skipUnless(os.environ.get('NARADA_SLOW_TESTS') == '1', 'Opt in to real 60-second TLS session rollover')
    def test_owner_can_approve_after_the_first_proof_tls_session_rolls_over(self):
        self.a.enable()
        self.b.enable()
        self.b.call('join_pairing', {'code': self.a.invite()['code']})
        wait_for(lambda: (self.a.snapshot()['invitation']['pending'] or {}).get('proof_verified'))
        time.sleep(65)
        self.assertEqual(self.b.snapshot()['join']['state'], 'awaiting_approval')
        attempt = self.a.snapshot()['invitation']['pending']['attempt_id']
        self.a.call('approve_pairing', {'attempt_id': attempt, 'permissions': NO_CONTENT})
        wait_for(lambda: any(p['state'] == 'active' and p['connection_state'] == 'online' for p in self.b.snapshot()['peers']))
        self.assertEqual(len(self.a.snapshot()['peers']), 1)

if __name__ == '__main__':
    unittest.main(verbosity=2)
