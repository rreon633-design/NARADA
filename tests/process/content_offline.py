#!/usr/bin/env python3
"""Desktop 5–7 real-process contract tests, plus a distinct OpenSSL negative peer.
Only disposable synthetic profiles. No external network and no production keys.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import socket
import tempfile
import time
import unittest
import uuid
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('pairing_security', ROOT/'tests/process/pairing_security.py')
p2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p2)

class ContentOfflineTests(unittest.TestCase):
    def setUp(self):
        (ROOT/'test-fixtures').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='content-', dir=ROOT/'test-fixtures')
        self.base = Path(self.temp.name)
        self.a = p2.Agent(self.base/'a')
        self.b = p2.Agent(self.base/'b')
        self.raw_peer = None
        self.source_dir = self.base/'source'; self.source_dir.mkdir()
        self.copy_dir = self.base/'copies'; self.copy_dir.mkdir()
        self.data = b'SYNTHETIC_ATTACHMENT_PLAINTEXT_73291_' * 18000
        (self.source_dir/'private-fixture-73291.bin').write_bytes(self.data)
        self.root = self.a.call('register_root', {'path': str(self.source_dir), 'label': 'Synthetic source', 'exclusions': []})['root_id']
        self.destination = self.b.call('register_root', {'path': str(self.copy_dir), 'label': 'Synthetic local copies', 'exclusions': []})['root_id']
    def tearDown(self):
        if self.raw_peer: self.raw_peer.close()
        self.b.stop(); self.a.stop(); self.temp.cleanup()
    def pair(self, attachments=True, sync=True):
        self.a.enable(); self.b.enable()
        self.b.call('join_pairing', {'code': self.a.invite()['code']})
        p2.wait_for(lambda: ((self.a.snapshot().get('invitation') or {}).get('pending') or {}).get('proof_verified'))
        pending = self.a.snapshot()['invitation']['pending']
        caps = ['browse', 'read'] + (['sync_read'] if sync else [])
        self.a.call('approve_pairing', {'attempt_id': pending['attempt_id'], 'permissions': {'chat': True, 'attachment': attachments, 'roots': [{'root_id': self.root, 'capabilities': caps}]}})
        p2.wait_for(lambda: any(p['state'] == 'active' for p in self.b.snapshot()['peers']))
        bp = next(p for p in self.b.snapshot()['peers'] if p['state'] == 'active')
        ap = next(p for p in self.a.snapshot()['peers'] if p['state'] == 'active')
        self.host, self.client = bp['peer_id'], ap['peer_id']
        self.b.call('update_peer_permissions', {'peer_id': self.host, 'expected_revision': bp['grant_revision'], 'permissions': {'chat': True, 'attachment': attachments, 'roots': []}})
    def source(self):
        self.a.call('reindex_root', {'root_id': self.root})
        entry = next(e for e in self.a.call('file_query', {'kind': 'browse', 'root_id': self.root, 'directory_id': None, 'cursor': None, 'sort': 'name'})['entries'] if e['name'] == 'private-fixture-73291.bin')
        return {'root_id': self.root, 'object_id': entry['object_id'], 'expected_version': entry['version']}
    def message(self, agent, peer, message_id):
        return next((m for m in agent.call('chat_history', {'peer_id': peer, 'before': None})['messages'] if m['message_id'] == message_id), None)
    def send(self, attachment=False, from_host=False, text='SYNTHETIC_PRIVATE_MESSAGE_81937'):
        agent, peer = (self.a, self.client) if from_host else (self.b, self.host)
        payload = {'message_id': str(uuid.uuid4()), 'peer_id': peer, 'text': text, 'attachment': self.source() if attachment else None}
        agent.call('send_chat', payload)
        return payload
    def received_attachment(self):
        payload = self.send(attachment=True, from_host=True)
        message = p2.wait_for(lambda: self.message(self.b, self.host, payload['message_id']), timeout=22)
        self.assertEqual(message['attachment']['state'], 'not_requested')
        return payload, message
    def pin(self, name='offline.bin'):
        payload = {'pin_id': str(uuid.uuid4()), 'peer_id': self.host, 'source': self.source(), 'destination': {'root_id': self.destination, 'directory_id': None, 'name': name}}
        self.b.call('create_pin', payload)
        return payload
    def pin_view(self, pin_id):
        return next(p for p in self.b.call('list_pins')['pins'] if p['pin_id'] == pin_id)
    def active_raw(self):
        self.a.enable(); code = json.loads(self.a.invite()['code']); raw = p2.RawPeer(self.base/'independent-peer'); self.raw_peer = raw
        claimed = raw.claim(code); self.assertTrue(claimed['ok'], claimed)
        raw.connect(f"127.0.0.1:{claimed['data']['api_port']}", code['fingerprint'], True); self.assertTrue(raw.proof()['ok'])
        self.a.call('approve_pairing', {'attempt_id': raw.attempt, 'permissions': p2.NO_CONTENT}); self.assertTrue(raw.proof(True)['ok'])
        return raw
    def test_two_agents_deliver_once_and_restart_keeps_protected_history(self):
        self.pair(); payload = self.send()
        p2.wait_for(lambda: self.message(self.b, self.host, payload['message_id'])['state'] == 'delivered', timeout=22)
        self.assertEqual(self.message(self.a, self.client, payload['message_id'])['text'], payload['text'])
        self.b.call('send_chat', payload)
        self.assertEqual(len(self.a.call('chat_history', {'peer_id': self.client, 'before': None})['messages']), 1)
        self.a.restart(); self.b.restart()
        self.assertEqual(self.message(self.b, self.host, payload['message_id'])['state'], 'delivered')
        self.assertEqual(self.message(self.a, self.client, payload['message_id'])['text'], payload['text'])
    def test_inbound_only_host_uses_authenticated_pull_not_an_invented_callback(self):
        self.pair(); self.assertIsNone(self.a.snapshot()['peers'][0]['endpoint'])
        payload = self.send(from_host=True)
        p2.wait_for(lambda: self.message(self.a, self.client, payload['message_id'])['state'] == 'delivered', timeout=22)
        self.assertEqual(self.message(self.b, self.host, payload['message_id'])['text'], payload['text'])
    def test_offline_outbox_does_not_claim_delivery_and_can_be_cancelled(self):
        self.pair(); self.a.stop()
        payload = self.send()
        message = self.message(self.b, self.host, payload['message_id']); self.assertIn(message['state'], ['pending', 'retry_wait'])
        self.b.call('chat_control', {'message_id': payload['message_id'], 'action': 'cancel'})
        self.assertEqual(self.message(self.b, self.host, payload['message_id'])['state'], 'cancelled')
    def test_message_id_conflict_and_byte_limit_fail_before_network_send(self):
        self.pair(); payload = self.send(); changed = {**payload, 'text': 'Different content'}
        self.assertEqual(self.b.raw('send_chat', changed)['error']['code'], 'MESSAGE_ID_CONFLICT')
        large = {**payload, 'message_id': str(uuid.uuid4()), 'text': 'é'*2049}
        self.assertEqual(self.b.raw('send_chat', large)['error']['code'], 'INVALID_MESSAGE')
    def test_draft_wal_and_safe_support_summary_do_not_leak_plaintext(self):
        self.pair(); sentinel = 'SYNTHETIC_DRAFT_WAL_PRIVACY_903251'; self.b.call('save_chat_draft', {'peer_id': self.host, 'text': sentinel})
        summary = self.b.call('support_bundle'); serialized = json.dumps(summary)
        self.assertFalse(summary['release_qualified']); self.assertTrue(summary['protected_content_ready'])
        for token in [sentinel, self.host, self.client, str(self.base), 'private-fixture-73291']: self.assertNotIn(token, serialized)
        for path in self.b.path.iterdir():
            if path.is_file(): self.assertNotIn(sentinel.encode(), path.read_bytes())
        self.b.restart(); self.assertEqual(self.b.call('chat_history', {'peer_id': self.host, 'before': None})['draft'], sentinel)
    def test_lost_content_key_is_visible_and_never_recreated(self):
        self.b.stop(); key = self.b.path/'content.key'; original = key.read_bytes(); key.unlink(); self.b.start()
        status = self.b.call('content_status'); self.assertFalse(status['ready']); self.assertEqual(status['error']['code'], 'CONTENT_STORE_LOCKED'); self.assertFalse(key.exists())
        self.b.stop(); key.write_bytes(original); os.chmod(key, 0o600); self.b.start(); self.assertTrue(self.b.call('content_status')['ready'])
    def test_independent_mtls_peer_has_no_content_without_scoped_consent(self):
        raw = self.active_raw()
        # Chat uses the peer-capability guard; sync uses the root-grant guard.
        # Both MUST deny consent-free access, with their exact documented codes.
        cases = [
            ('/api/v1/chat', {'hello': p2.HELLO, 'version': 1, 'action': {'kind': 'bundle'}}, 'PERMISSION_DENIED'),
            ('/api/v1/sync', {'hello': p2.HELLO, 'version': 1, 'request': {'kind': 'snapshot', 'root_id': self.root, 'cursor': None}}, 'ACCESS_DENIED'),
        ]
        for path, body, expected_code in cases:
            with self.subTest(path=path):
                time.sleep(.15)
                response = raw.post(path, body)
                self.assertFalse(response['ok'])
                self.assertEqual(response['error']['code'], expected_code)
                self.assertIsNone(response.get('data'), 'Denied requests must not return content')
        raw.connect(self.a.snapshot()['endpoint'], raw.code['fingerprint'], True)
        response = raw.post('/api/v1/content_status', {'hello': p2.HELLO}); self.assertEqual(response['error']['code'], 'NO_PEER_ROUTE')
    def test_attachment_delivery_precedes_explicit_ciphertext_download_and_export(self):
        self.pair(); payload, message = self.received_attachment(); message_id = payload['message_id']
        self.assertFalse((self.copy_dir/'exported.bin').exists())
        with sqlite3.connect(self.b.path/'narada.db') as db: self.assertEqual(db.execute('SELECT COUNT(*) FROM transfer_receives').fetchone()[0], 0)
        self.b.call('fetch_attachment', {'message_id': message_id})
        p2.wait_for(lambda: self.message(self.b, self.host, message_id)['attachment']['state'] == 'available', timeout=22)
        operation = {'message_id': message_id, 'operation_id': str(uuid.uuid4()), 'destination': {'root_id': self.destination, 'directory_id': None, 'name': 'exported.bin'}}
        receipt = self.b.call('export_attachment', operation); self.assertEqual(receipt['state'], 'completed'); self.assertEqual((self.copy_dir/'exported.bin').read_bytes(), self.data); self.assertEqual(self.b.call('export_attachment', operation), receipt)
        self.assertEqual(self.message(self.b, self.host, message_id)['attachment']['state'], 'exported')
    def test_attachment_pause_restart_resume_uses_original_snapshot(self):
        self.data = bytes(range(251))*22000; (self.source_dir/'private-fixture-73291.bin').write_bytes(self.data)
        self.pair(); payload, _ = self.received_attachment(); message_id = payload['message_id']
        (self.source_dir/'private-fixture-73291.bin').write_bytes(b'CHANGED_SOURCE_AFTER_ENCRYPTION')
        self.b.call('fetch_attachment', {'message_id': message_id}); self.b.call('pause_attachment', {'message_id': message_id}); self.b.restart()
        self.assertEqual(self.message(self.b, self.host, message_id)['attachment']['state'], 'paused')
        self.b.call('fetch_attachment', {'message_id': message_id}); p2.wait_for(lambda: self.message(self.b, self.host, message_id)['attachment']['state'] == 'available', timeout=25)
        self.b.call('export_attachment', {'message_id': message_id, 'operation_id': str(uuid.uuid4()), 'destination': {'root_id': self.destination, 'directory_id': None, 'name': 'resumed.bin'}})
        self.assertEqual((self.copy_dir/'resumed.bin').read_bytes(), self.data)
    def test_attachment_and_chat_permissions_are_independent(self):
        self.pair(attachments=False)
        payload = {'message_id': str(uuid.uuid4()), 'peer_id': self.client, 'text': 'No attachment permission', 'attachment': self.source()}
        self.assertEqual(self.a.raw('send_chat', payload)['error']['code'], 'PERMISSION_DENIED')
        peer = self.b.snapshot()['peers'][0]
        self.b.call('update_peer_permissions', {'peer_id': peer['peer_id'], 'expected_revision': peer['grant_revision'], 'permissions': p2.NO_CONTENT})
        self.assertEqual(self.b.raw('send_chat', {'message_id': str(uuid.uuid4()), 'peer_id': self.host, 'text': 'No chat permission', 'attachment': None})['error']['code'], 'PERMISSION_DENIED')
    def test_pin_download_is_an_actual_verified_local_copy(self):
        self.pair(); pin = self.pin(); p2.wait_for(lambda: self.pin_view(pin['pin_id'])['current_receipt'], timeout=25)
        self.assertEqual((self.copy_dir/'offline.bin').read_bytes(), self.data)
        receipt = self.pin_view(pin['pin_id'])['current_receipt']; self.assertEqual(receipt['sha256'], hashlib.sha256(self.data).hexdigest())
        self.a.stop(); self.assertEqual((self.copy_dir/'offline.bin').read_bytes(), self.data)
    def test_pin_refresh_and_source_deletion_keep_previous_edited_copy(self):
        self.pair(); pin = self.pin(); pin_id = pin['pin_id']; p2.wait_for(lambda: self.pin_view(pin_id)['current_receipt'], timeout=25)
        (self.copy_dir/'offline.bin').write_bytes(b'USER_LOCAL_EDITS')
        changed = b'New selected source version, never mixed with the previous one.'; (self.source_dir/'private-fixture-73291.bin').write_bytes(changed); self.a.call('reindex_root', {'root_id': self.root}); self.b.call('refresh_sync')
        p2.wait_for(lambda: len(self.pin_view(pin_id)['versions']) == 2, timeout=30)
        name = self.pin_view(pin_id)['current_receipt']['name']; self.assertNotEqual(name, 'offline.bin'); self.assertEqual((self.copy_dir/name).read_bytes(), changed); self.assertEqual((self.copy_dir/'offline.bin').read_bytes(), b'USER_LOCAL_EDITS')
        (self.source_dir/'private-fixture-73291.bin').unlink(); self.a.call('reindex_root', {'root_id': self.root}); self.b.call('refresh_sync')
        p2.wait_for(lambda: self.pin_view(pin_id)['state'] == 'source_deleted', timeout=25); self.assertTrue((self.copy_dir/name).exists()); self.assertEqual((self.copy_dir/'offline.bin').read_bytes(), b'USER_LOCAL_EDITS')
    def test_read_permission_alone_does_not_authorize_sync_pins(self):
        self.pair(sync=False)
        payload = {'pin_id': str(uuid.uuid4()), 'peer_id': self.host, 'source': self.source(), 'destination': {'root_id': self.destination, 'directory_id': None, 'name': 'denied.bin'}}
        self.assertEqual(self.b.raw('create_pin', payload)['error']['code'], 'ACCESS_DENIED'); self.assertFalse((self.copy_dir/'denied.bin').exists())
    def test_pin_unpin_and_cleanup_never_remove_published_files(self):
        self.pair(); pin = self.pin(); p2.wait_for(lambda: self.pin_view(pin['pin_id'])['current_receipt'], timeout=25)
        self.b.call('pin_control', {'pin_id': pin['pin_id'], 'action': 'unpin'}); self.b.call('cleanup_transfers'); self.b.call('cleanup_content')
        self.assertEqual(self.b.call('list_pins')['pins'], []); self.assertEqual((self.copy_dir/'offline.bin').read_bytes(), self.data)
    def test_metadata_snapshot_cursor_rejects_wrong_root_and_restart(self):
        first = self.a.call('sync_query', {'kind': 'snapshot', 'root_id': self.root, 'cursor': None}); cursor = first['changes_cursor']; self.assertTrue(cursor)
        wrong = self.b.raw('sync_query', {'kind': 'changes', 'root_id': self.destination, 'cursor': cursor}); self.assertEqual(wrong['error']['code'], 'CURSOR_EXPIRED')
        self.a.restart(); after = self.a.raw('sync_query', {'kind': 'changes', 'root_id': self.root, 'cursor': cursor}); self.assertEqual(after['error']['code'], 'CURSOR_EXPIRED')
    def test_owner_payload_unknown_fields_and_traversal_never_reach_filesystem(self):
        # Malformed frames deliberately have no trusted correlation ID. The
        # normal Agent.raw helper must continue enforcing IDs for valid frames.
        message = {'version': 1, 'id': str(uuid.uuid4()), 'command': {'type': 'send_chat', 'payload': {'message_id': str(uuid.uuid4()), 'peer_id': 'a'*64, 'text': 'Synthetic parser fixture', 'attachment': None, 'path': '/etc/passwd'}}}
        with socket.socket(socket.AF_UNIX) as connection:
            connection.settimeout(8)
            connection.connect(str(self.b.path/'agent.sock'))
            connection.sendall(json.dumps(message).encode()+b'\n')
            frame=b''
            while not frame.endswith(b'\n'):
                chunk=connection.recv(65536)
                self.assertTrue(chunk, 'Malformed-frame response ended prematurely')
                frame+=chunk
                self.assertLessEqual(len(frame),65536)
        result=json.loads(frame)
        self.assertFalse(result['ok'])
        self.assertEqual(result['version'],1)
        self.assertEqual(result['id'],'')
        self.assertEqual(result['error']['code'],'INVALID_REQUEST')
        self.assertNotIn('Synthetic parser fixture',json.dumps(result))
        bad = self.b.raw('export_attachment', {'message_id': str(uuid.uuid4()), 'operation_id': '../outside', 'destination': {'root_id': self.destination, 'directory_id': None, 'name': '../outside'}})
        self.assertFalse(bad['ok']); self.assertFalse((self.base/'outside').exists())

    def test_total_private_store_loss_cannot_reset_identity_after_config_changes(self):
        # Added after the single recorded pass; not executed in that pass.
        self.b.enable()
        self.b.call('configure_connections', {'enabled': False, 'bind_ip': '127.0.0.1', 'api_port': 0, 'discovery': False})
        self.b.stop()
        for name in ['content.key','content.db','content.db-wal','content.db-shm','content.guard']:
            (self.b.path/name).unlink(missing_ok=True)
        self.b.start()
        status=self.b.call('content_status')
        self.assertFalse(status['ready'])
        self.assertEqual(status['error']['code'],'CONTENT_STORE_LOCKED')
        self.assertEqual(self.b.snapshot()['identity']['state'],'ready')
        for name in ['content.key','content.db','content.guard']:
            self.assertFalse((self.b.path/name).exists())

    def test_unmarked_existing_profile_requires_explicit_new_enrollment(self):
        # Covers legacy/ambiguous profiles rather than guessing that lost state
        # never existed. Added after the pass, not executed here.
        self.b.stop()
        for name in ['content.key','content.db','content.db-wal','content.db-shm','content.guard']:
            (self.b.path/name).unlink(missing_ok=True)
        with sqlite3.connect(self.b.path/'narada.db') as database:
            database.execute("UPDATE connection_config SET document=json_remove(document,'$.content_identity') WHERE id=1")
        self.b.start()
        status=self.b.call('content_status')
        self.assertFalse(status['ready'])
        self.assertEqual(status['error']['code'],'CONTENT_STORE_LOCKED')
        for name in ['content.key','content.db','content.guard']:
            self.assertFalse((self.b.path/name).exists())

if __name__ == '__main__': unittest.main(verbosity=2)
