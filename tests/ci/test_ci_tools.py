import importlib.util
import os
from pathlib import Path
import struct
import sys
import tempfile
import tomllib
import xml.etree.ElementTree as ET
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
import evidence
import artifacts
import emulator


class EvidenceTests(unittest.TestCase):
    def xml(self, text):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / 'TEST-fixture.xml'
        path.write_text(text)
        return path

    def test_missing_xml_cannot_pass(self):
        with self.assertRaises(RuntimeError): evidence.junit_stats([])

    def test_zero_cases_cannot_pass(self):
        with self.assertRaises(RuntimeError): evidence.junit_stats([self.xml('<testsuite tests="10"/>')])

    def test_leaf_case_counts_are_not_inflated_by_suite_headers(self):
        s = evidence.junit_stats([self.xml('<testsuites tests="100"><testsuite tests="100"><testcase name="one"/><testcase name="two"><skipped/></testcase></testsuite></testsuites>')])
        self.assertEqual({'tests': 2, 'passed': 1, 'skipped': 1, 'errors': 0, 'failures': 0}, s)

    def test_suite_failure_not_hidden_by_missing_cases(self):
        with self.assertRaises(RuntimeError): evidence.junit_stats([self.xml('<testsuite failures="1"><testcase name="one"/></testsuite>')])

    def test_failed_case_is_not_pass(self):
        s = evidence.junit_stats([self.xml('<testsuite><testcase><failure/></testcase></testsuite>')])
        self.assertEqual(1, s['failures'])
        self.assertEqual(0, s['passed'])

    def test_abi_is_per_architecture(self):
        self.assertEqual('arm64-v8a', artifacts.abi_in_zip(['lib/arm64-v8a/libnarada_age.so', 'classes.dex']))
        for names in ([], ['lib/mips/foo.so'], ['lib/arm64-v8a/a.so', 'lib/x86_64/b.so']):
            with self.assertRaises(RuntimeError): artifacts.abi_in_zip(names)

    def test_elf_machine_and_16k_alignment(self):
        data = bytearray(120)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', data, 18, 183)
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<I', data, 64, 1)
        struct.pack_into('<Q', data, 112, 16384)
        self.assertEqual([16384], artifacts.elf_check(data, 'arm64-v8a')['load_alignments'])
        with self.assertRaises(RuntimeError): artifacts.elf_check(data, 'x86_64')
        struct.pack_into('<Q', data, 112, 4096)
        with self.assertRaises(RuntimeError): artifacts.elf_check(data, 'arm64-v8a')

    def test_native_adb_exit_zero_is_not_test_success(self):
        for text in ('INSTRUMENTATION_CODE: -1', 'OK (0 tests)\nINSTRUMENTATION_CODE: -1', 'OK (13 tests)\nFAILURES!!!\nINSTRUMENTATION_CODE: -1', 'OK (13 tests)'):
            with self.assertRaises(RuntimeError): emulator.parse_result(text)
        progress = 'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n' * 13
        self.assertEqual(13, emulator.parse_result(progress + 'OK (13 tests)\n\nINSTRUMENTATION_CODE: -1\n'))
        with self.assertRaises(RuntimeError): emulator.parse_result(progress.replace('CODE: 0', 'CODE: -3', 1) + 'OK (13 tests)\nINSTRUMENTATION_CODE: -1\n')

    def test_size_guards_are_enabled(self):
        self.assertLessEqual(artifacts.POLICY['android_apk_max_bytes'], 20 * 1024 * 1024)
        self.assertLessEqual(artifacts.POLICY['windows_installer_max_bytes'], 30 * 1024 * 1024)
        self.assertEqual(4, len(set(artifacts.POLICY['android_abis'])))

    def test_small_webview_bootstrappers_are_not_bundled_browser_runtimes(self):
        for mode in ('downloadBootstrapper', 'embedBootstrapper'):
            self.assertEqual(mode, artifacts.webview_mode({'bundle': {'windows': {'webviewInstallMode': {'type': mode}}}}))

    def test_bundled_or_unchecked_webview_runtimes_are_rejected(self):
        for mode in ('offlineInstaller', 'fixedRuntime', 'skip', 'unknown'):
            with self.subTest(mode=mode), self.assertRaises(RuntimeError):
                artifacts.webview_mode({'bundle': {'windows': {'webviewInstallMode': {'type': mode}}}})

    def test_instrumentation_pins_keep_agp_runtime_consistency_and_shrinking(self):
        versions = tomllib.loads((ROOT / 'apps/android/gradle/libs.versions.toml').read_text())['versions']
        self.assertEqual('1.3.0', versions['concurrent-futures'])
        self.assertEqual('2.51.0', versions['test-errorprone'])
        build = (ROOT / 'apps/android/app/build.gradle.kts').read_text()
        self.assertIn('implementation(libs.concurrent.futures.ktx)', build)
        self.assertIn('androidTestImplementation(libs.test.errorprone)', build)
        self.assertNotIn('    implementation(libs.test.errorprone)', build)
        self.assertIn('isMinifyEnabled = true', build)
        self.assertIn('isShrinkResources = true', build)
        locks = dict(line.split('=', 1) for line in (ROOT / 'apps/android/app/gradle.lockfile').read_text().splitlines() if line and not line.startswith('#'))
        for variant in ('debug', 'manualTest'):
            for module in ('androidx.concurrent:concurrent-futures:1.3.0', 'androidx.concurrent:concurrent-futures-ktx:1.3.0'):
                self.assertIn(variant + 'RuntimeClasspath', locks[module].split(','))
                self.assertIn(variant + 'AndroidTestRuntimeClasspath', locks[module].split(','))
            self.assertIn(variant + 'AndroidTestRuntimeClasspath', locks['com.google.errorprone:error_prone_annotations:2.51.0'].split(','))

    def test_instrumentation_runtime_jars_have_the_inspected_checksum_pins(self):
        ns = {'v': 'https://schema.gradle.org/dependency-verification'}
        root = ET.parse(ROOT / 'apps/android/gradle/verification-metadata.xml').getroot()
        expected = {
            ('androidx.concurrent', 'concurrent-futures', '1.3.0'): '9111fab38c19cac12f507ce1fed1875c155fd201f5cd5b8c7f3b7e2b18d3fc49',
            ('androidx.concurrent', 'concurrent-futures-ktx', '1.3.0'): '8de3069a4602d487b63ba061c7d46c78739e7ae5bf3dfd14bfbb9ae80277b1d9',
            ('com.google.errorprone', 'error_prone_annotations', '2.51.0'): '51a6d8348c7e307e47183859c07597dc8a2899c5d1b6f18a471bccaa3203d5cf',
        }
        for (group, name, version), sha in expected.items():
            component = root.find(f"v:components/v:component[@group='{group}'][@name='{name}'][@version='{version}']", ns)
            self.assertIsNotNone(component)
            artifact = component.find(f"v:artifact[@name='{name}-{version}.jar']", ns)
            self.assertIsNotNone(artifact)
            self.assertEqual([sha], [value.get('value') for value in artifact.findall('v:sha256', ns)])

    def test_historical_directory_is_never_redirected_locally(self):
        old = os.environ.get('CI')
        os.environ['CI'] = 'false'
        try:
            with self.assertRaises(RuntimeError): evidence.browser_redirect()
        finally:
            if old is None: os.environ.pop('CI', None)
            else: os.environ['CI'] = old


if __name__ == '__main__': unittest.main(verbosity=2)
