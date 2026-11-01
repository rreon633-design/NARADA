"""Linux development-agent integration tests. No native Windows claims.
Run after: cargo build -p narada-agent --features dev-preview
"""
import json
import os
from pathlib import Path
import signal
import socket
import sqlite3
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / 'target/debug/narada-agent'
SCRATCH = ROOT / '.cache/process-tests'
SCRATCH.mkdir(parents=True, exist_ok=True)


def request(data, kind='get_snapshot', payload=None, version=1):
    command = {'type': kind}
    if payload is not None:
        command['payload'] = payload
    envelope = {'version': version, 'id': str(uuid.uuid4()), 'command': command}
    with socket.socket(socket.AF_UNIX) as client:
        client.settimeout(3)
        client.connect(str(data / 'agent.sock'))
        client.sendall(json.dumps(envelope).encode() + b'\n')
        with client.makefile('rb') as reader:
            raw = reader.readline(65537)
    response = json.loads(raw)
    assert response['id'] == envelope['id']
    return response


@unittest.skipUnless(os.name == 'posix', 'Unix development adapter only; use Windows native checklist')
class AgentLifecycle(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='a-', dir=SCRATCH)
        self.data = Path(self.temp.name)
        self.processes = []

    def tearDown(self):
        for process in self.processes:
            if process.poll() is None:
                process.send_signal(signal.SIGINT)
            try:
                process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
        self.temp.cleanup()

    def spawn(self, *args, env=None):
        process = subprocess.Popen([str(BINARY), '--data-dir', str(self.data), *args],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                   env={**os.environ, **(env or {})})
        self.processes.append(process)
        return process

    def start(self):
        process = self.spawn()
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if process.poll() is not None:
                self.fail(process.communicate()[0])
            try:
                if request(self.data)['ok']:
                    return process
            except (OSError, ValueError):
                pass
            time.sleep(.025)
        self.fail('Agent did not become ready within eight seconds')

    def stop(self, process):
        self.assertTrue(request(self.data, 'shutdown')['ok'])
        logs = process.communicate(timeout=10)[0]
        self.assertEqual(process.returncode, 0, logs)
        self.assertFalse((self.data / 'agent.sock').exists())
        return logs

    def test_clean_restart_preserves_preferences_and_diagnostics(self):
        process = self.start()
        settings = request(self.data)['data']['settings']
        settings.update(device_name='PRIVATE_SENTINEL_NAME', reduce_motion=True)
        self.assertTrue(request(self.data, 'save_settings', settings)['ok'])
        report = request(self.data, 'run_diagnostics')['data']
        logs = self.stop(process)
        self.assertNotIn('PRIVATE_SENTINEL_NAME', logs)
        self.assertNotIn(str(self.data), logs)
        second = self.start()
        snapshot = request(self.data)['data']
        self.assertEqual(snapshot['settings'], settings)
        self.assertEqual(snapshot['last_diagnostics'], report)
        self.stop(second)

    def test_acknowledged_write_survives_forced_process_death(self):
        process = self.start()
        settings = request(self.data)['data']['settings']
        settings['device_name'] = 'After a forced restart'
        self.assertTrue(request(self.data, 'save_settings', settings)['ok'])
        process.kill()
        process.communicate(timeout=10)
        second = self.start()
        self.assertEqual(request(self.data)['data']['settings'], settings)
        self.assertTrue(request(self.data, 'run_diagnostics')['ok'])
        self.stop(second)

    def test_single_agent_lock_does_not_replace_running_owner(self):
        process = self.start()
        other = self.spawn()
        logs = other.communicate(timeout=10)[0]
        self.assertNotEqual(other.returncode, 0)
        self.assertIn('ALREADY_RUNNING', logs)
        self.assertIsNone(process.poll())
        self.assertTrue(request(self.data)['ok'])
        self.stop(process)

    def test_short_lived_client_exit_does_not_end_agent(self):
        process = self.start()
        cli = subprocess.run([str(BINARY), '--data-dir', str(self.data), '--status'],
                             capture_output=True, text=True, timeout=10)
        self.assertEqual(cli.returncode, 0)
        self.assertEqual(json.loads(cli.stdout)['agent']['peer_network_enabled'], False)
        self.assertIsNone(process.poll())
        self.assertTrue(request(self.data)['ok'])
        self.stop(process)

    def test_version_and_invalid_preferences_cannot_mutate_state(self):
        process = self.start()
        original = request(self.data)['data']['settings']
        bad = {**original, 'device_name': ''}
        self.assertEqual(request(self.data, 'save_settings', bad)['error']['code'], 'INVALID_SETTINGS')
        self.assertEqual(request(self.data, 'save_settings', {**original, 'device_name': 'Wrong version'}, version=99)['error']['code'], 'PROTOCOL_INCOMPATIBLE')
        self.assertEqual(request(self.data)['data']['settings'], original)
        self.stop(process)

    def test_unix_permissions_and_sqlite_migrations(self):
        process = self.start()
        self.assertEqual(self.data.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.data / 'agent.sock').stat().st_mode & 0o777, 0o600)
        with sqlite3.connect(self.data / 'narada.db') as db:
            self.assertEqual(db.execute('PRAGMA quick_check').fetchone()[0], 'ok')
            self.assertEqual(db.execute('SELECT count(*) FROM _sqlx_migrations WHERE success=1').fetchone()[0], 6)
        self.stop(process)

    def test_corrupt_metadata_is_not_silently_reset(self):
        original = b'This is not a valid SQLite database.' * 200
        path = self.data / 'narada.db'
        path.write_bytes(original)
        process = self.spawn()
        logs = process.communicate(timeout=10)[0]
        self.assertNotEqual(process.returncode, 0)
        self.assertIn('STORAGE_UNAVAILABLE', logs)
        self.assertEqual(path.read_bytes(), original)

    def test_preview_bind_failure_is_observed_and_agent_stops(self):
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', 0))
            occupied.listen()
            process = self.spawn('--preview-port', str(occupied.getsockname()[1]), env={'NARADA_DEV_TOKEN': 'test-only-token-' * 4})
            logs = process.communicate(timeout=10)[0]
        self.assertNotEqual(process.returncode, 0)
        self.assertIn('PREVIEW_START_FAILED', logs)
        self.assertNotIn('test-only-token-', logs)
        next_process = self.start()
        self.stop(next_process)

    def test_preview_requires_a_long_server_only_token(self):
        process = self.spawn('--preview-port', '4999', env={'NARADA_DEV_TOKEN': 'short'})
        logs = process.communicate(timeout=10)[0]
        self.assertNotEqual(process.returncode, 0)
        self.assertIn('PREVIEW_TOKEN_REQUIRED', logs)

    def test_sigterm_is_a_clean_shutdown_on_development_host(self):
        process = self.start()
        process.terminate()
        logs = process.communicate(timeout=10)[0]
        self.assertEqual(process.returncode, 0, logs)
        self.assertIn('stopped cleanly', logs)
        self.assertFalse((self.data / 'agent.sock').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
