import copy
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import textwrap
import unittest

from scripts.architecture.check_guardrails import evaluate_frontier, validate_manifest


def policy():
    return dict(schema_version=1, frontier_id='synthetic', branch='feat/synthetic',
                allowed_paths=['settings.gradle.kts'], required_artifacts=[],
                forbidden_content_patterns=['retrofit'])


DEVICE_IDENTITY_ALLOWED_PATHS = {
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle/libs.versions.toml',
    'app/build.gradle.kts',
    'app/src/main/java/io/github/escossio/andy/MainActivity.kt',
    'core/device-identity/build.gradle.kts',
    'core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt',
    'core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt',
    'core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt',
    'core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt',
    'data/device-identity/build.gradle.kts',
    'data/device-identity/src/main/AndroidManifest.xml',
    'data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt',
    'data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt',
    'data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt',
    'data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt',
}


HUMAN_IDENTITY_ALLOWED_PATHS = {
    'settings.gradle.kts',
    'gradle/libs.versions.toml',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/io/github/escossio/andy/MainActivity.kt',
    'app/src/main/res/values/strings.xml',
    'core/human-identity/build.gradle.kts',
    'core/human-identity/src/main/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentity.kt',
    'core/human-identity/src/main/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentityEngine.kt',
    'core/human-identity/src/test/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentityEngineTest.kt',
    'sdk/client-api/build.gradle.kts',
    'sdk/client-api/src/main/kotlin/io/github/escossio/andy/sdk/clientapi/AttentionRouterHumanAuthClient.kt',
    'sdk/client-api/src/test/kotlin/io/github/escossio/andy/sdk/clientapi/AttentionRouterHumanAuthClientTest.kt',
    'integrations/google-identity/build.gradle.kts',
    'integrations/google-identity/src/main/AndroidManifest.xml',
    'integrations/google-identity/src/main/java/io/github/escossio/andy/integrations/googleidentity/GoogleCredentialAcquirer.kt',
    'integrations/google-identity/src/test/java/io/github/escossio/andy/integrations/googleidentity/GoogleCredentialAcquirerTest.kt',
    'features/onboarding/build.gradle.kts',
    'features/onboarding/src/main/AndroidManifest.xml',
    'features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinator.kt',
    'features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/OnboardingScreen.kt',
    'features/onboarding/src/test/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinatorTest.kt',
}


def device_identity_manifest():
    path = Path(__file__).resolve().parents[2] / '.github/architecture/frontiers/android-device-identity-v1.json'
    return json.loads(path.read_text())


def human_identity_manifest():
    path = Path(__file__).resolve().parents[2] / '.github/architecture/frontiers/android-human-identity-v1.json'
    return json.loads(path.read_text())


class GuardPolicyTests(unittest.TestCase):
    def assert_kvm_preflight(self, text):
        job = self.jobs(text)['android-instrumentation']
        step = self.step(job, 'Verify KVM access and acceleration')
        self.assert_step_condition(step, "steps.device_identity.outputs.present == 'true'")
        self.assertIn('timeout-minutes: 1', step)
        self.assertLess(job.index(step), job.index(self.step(job, 'Create and boot API 36 emulator')))
        self.assertIn('timeout -k 1s 10s "$EMULATOR" -accel-check', step)
        allowed = '            sudo chmod 0666 /dev/kvm\n'
        self.assertEqual(step.count(allowed), 1)
        # Remove only the exact standalone exception in this specific step.
        remainder = text.replace(step, step.replace(allowed, ''), 1)
        self.assertNotRegex(remainder, r'\bsudo\b|\bchmod\b')

    def test_kvm_preflight_allows_only_exact_scoped_sudo(self):
        text = self.workflow()
        self.assert_kvm_preflight(text)
        for command in ('sudo apt update', 'sudo usermod runner', 'sudo gpasswd runner',
                        'sudo groupadd other', 'sudo chown runner /dev/kvm', 'sudo sh',
                        'sudo chmod 0666 /tmp/other', 'sudo chmod 0666 /dev/kvm; true'):
            with self.subTest(command=command), self.assertRaises(AssertionError):
                self.assert_kvm_preflight(text.replace('sudo chmod 0666 /dev/kvm', command))
        with self.assertRaises(AssertionError):
            self.assert_kvm_preflight(text + '\n      - run: sudo chmod 0666 /dev/kvm\n')

    def run_kvm_fixture(self, mode):
        job = self.jobs(self.workflow())['android-instrumentation']
        step = self.step(job, 'Verify KVM access and acceleration')
        script = textwrap.dedent(step.split('        run: |\n', 1)[1])
        # Shell doubles model permissions without touching the real KVM device or sudo.
        prefix = '''
access=no
[[ "$KVM_FIXTURE" == accessible || "$KVM_FIXTURE" == accel_failure ]] && access=yes
timeout() {
  case "$4" in
    id|ls) echo synthetic-host-evidence ;;
    *) command timeout "$@" ;;
  esac
}
test() {
  if [[ "$2" != /dev/kvm ]]; then builtin test "$@"; return; fi
  case "$1" in
    -e) [[ "$KVM_FIXTURE" != absent ]] ;;
    -r|-w) [[ "$access" == yes ]] ;;
    *) return 2 ;;
  esac
}
sudo() {
  printf 'privileged-command=%s\\n' "$*"
  [[ "$*" == 'chmod 0666 /dev/kvm' ]] || return 2
  if [[ "$KVM_FIXTURE" != denied ]]; then access=yes; fi
}
'''
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / 'emulator/emulator'
            executable.parent.mkdir()
            executable.write_text('#!/bin/bash\n'
                                  '[[ "$*" == -accel-check ]] || exit 2\n'
                                  'echo fixture-accel-check\n'
                                  '[[ "$KVM_FIXTURE" != accel_failure ]]\n')
            executable.chmod(0o755)
            env = dict(os.environ, ANDROID_SDK_ROOT=temporary, KVM_FIXTURE=mode)
            result = subprocess.run(['bash', '-c', prefix + script], env=env,
                                    capture_output=True, text=True, timeout=5)
            return result.returncode, result.stdout + result.stderr

    def test_kvm_missing_device_fails_before_acceleration(self):
        status, output = self.run_kvm_fixture('absent')
        self.assertNotEqual(status, 0)
        self.assertIn('KVM_DEVICE_MISSING', output)
        self.assertNotIn('privileged-command=', output)
        self.assertNotIn('fixture-accel-check', output)

    def test_kvm_existing_access_never_invokes_sudo(self):
        status, output = self.run_kvm_fixture('accessible')
        self.assertEqual(status, 0, output)
        self.assertNotIn('privileged-command=', output)
        self.assertIn('fixture-accel-check', output)

    def test_kvm_missing_access_uses_exact_remediation(self):
        status, output = self.run_kvm_fixture('remediate')
        self.assertEqual(status, 0, output)
        self.assertEqual(output.count('privileged-command=chmod 0666 /dev/kvm'), 1)
        self.assertIn('fixture-accel-check', output)

    def test_kvm_failed_remediation_fails_closed(self):
        status, output = self.run_kvm_fixture('denied')
        self.assertNotEqual(status, 0)
        self.assertIn('KVM_ACCESS_UNAVAILABLE', output)
        self.assertNotIn('fixture-accel-check', output)

    def test_kvm_acceleration_failure_blocks_avd_flow(self):
        status, output = self.run_kvm_fixture('accel_failure')
        self.assertNotEqual(status, 0)
        self.assertIn('KVM_ACCEL_CHECK_FAILED', output)

    def assert_boot_diagnostics(self, text):
        job = self.jobs(text)['android-instrumentation']
        boot = self.step(job, 'Create and boot API 36 emulator')
        self.assert_step_condition(boot, "steps.device_identity.outputs.present == 'true'")
        for required in (
            'timeout-minutes: 7',
            'EMULATOR_PID=$!',
            'boot_deadline=$((SECONDS + 240))',
            'while (( SECONDS < boot_deadline )); do',
            'kill -0 "$EMULATOR_PID"',
            'timeout -k 1s 3s "$ADB" wait-for-device',
            'timeout -k 1s 3s "$ADB" shell getprop sys.boot_completed',
            'timeout -k 1s 60s "$AVDMANAGER" create avd',
            "trap 'status=$?; if (( status != 0 )); then boot_diagnostics; fi' EXIT",
            'echo "=== emulator process ==="',
            'ps -p "${EMULATOR_PID:-$$}" -o pid,ppid,stat,args || true',
            'echo "=== adb devices ==="',
            'timeout -k 1s 10s "$ADB" devices -l || true',
            'echo "=== emulator log ==="',
            'cat "$RUNNER_TEMP/andy-emulator.log" || true',
            'test -e /dev/kvm && ls -l /dev/kvm || true',
        ):
            self.assertIn(required, boot)
        self.assertNotRegex(boot, r'(?m)^\s*"\$ADB" (wait-for-device|shell)\b')
        cleanup = self.step(job, 'Stop emulator')
        self.assert_step_condition(cleanup,
                                   "always() && steps.device_identity.outputs.present == 'true'")
        self.assertIn('"$SDK_ROOT/platform-tools/adb" emu kill || true', cleanup)
        self.assertNotIn('emulator -kill', cleanup)

    def test_emulator_cleanup_uses_console_command_and_rejects_invalid_form(self):
        text = self.workflow()
        job = self.jobs(text)['android-instrumentation']
        cleanup = self.step(job, 'Stop emulator')
        expected = '"$SDK_ROOT/platform-tools/adb" emu kill || true'
        self.assertEqual(cleanup.split('        run: |\n', 1)[1].strip().splitlines()[-1].strip(),
                         expected)
        self.assert_boot_diagnostics(text)
        for invalid in ('"$SDK_ROOT/platform-tools/adb" emulator -kill || true',
                        'adb emulator -kill || true'):
            with self.subTest(command=invalid), self.assertRaises(AssertionError):
                self.assert_boot_diagnostics(text.replace(expected, invalid))

    def test_emulator_boot_requires_bounded_observable_wait(self):
        self.assert_boot_diagnostics(self.workflow())

    def test_emulator_boot_rejects_missing_diagnostic_safeguards(self):
        text = self.workflow()
        self.assert_boot_diagnostics(text)
        for original in (
            'EMULATOR_PID=$!', 'kill -0 "$EMULATOR_PID"',
            'while (( SECONDS < boot_deadline )); do',
            'timeout -k 1s 3s', 'timeout -k 1s 60s',
            'timeout -k 1s 10s', 'timeout-minutes: 7',
            'boot_diagnostics; fi', 'cat "$RUNNER_TEMP/andy-emulator.log" || true',
            'ps -p "${EMULATOR_PID:-$$}" -o pid,ppid,stat,args || true',
            'test -e /dev/kvm && ls -l /dev/kvm || true',
            "always() && steps.device_identity.outputs.present == 'true'",
        ):
            with self.subTest(removed=original), self.assertRaises(AssertionError):
                self.assert_boot_diagnostics(text.replace(original, ''))

    def test_avd_creation_requires_explicit_device_and_discovery(self):
        job = self.jobs(self.workflow())['android-instrumentation']
        boot = self.step(job, 'Create and boot API 36 emulator')
        self.assertNotIn('echo no |', boot)
        for required in ('timeout -k 1s 10s "$AVDMANAGER" list device -c',
                         'timeout -k 1s 10s "$AVDMANAGER" list avd -c',
                         '--device "$DEVICE_ID"',
                         'NO_SUPPORTED_AVD_DEVICE_DEFINITION',
                         'AVD_CREATION_POSTCONDITION_FAILED'):
            self.assertIn(required, boot)
        self.assertLess(boot.index('list avd -c'), boot.index('"$EMULATOR" \\\n'))

    def test_avd_creation_prefers_pixel(self):
        status, output = self.run_boot_fixture('success', devices='pixel_xl\npixel\n')
        self.assertEqual(status, 0, output)
        self.assertIn('selected-device=pixel\n', output)
        self.assertIn('fixture-emulator-launched', output)

    def test_avd_creation_falls_back_to_pixel_xl(self):
        status, output = self.run_boot_fixture('success', devices='pixel_xl\n')
        self.assertEqual(status, 0, output)
        self.assertIn('selected-device=pixel_xl\n', output)
        self.assertIn('fixture-emulator-launched', output)

    def test_avd_creation_rejects_unsupported_devices_before_create(self):
        status, output = self.run_boot_fixture('success', devices='pixel_2\npixel_xl_extra\n')
        self.assertNotEqual(status, 0, output)
        self.assertIn('NO_SUPPORTED_AVD_DEVICE_DEFINITION', output)
        self.assertNotIn('selected-device=', output)
        self.assertNotIn('fixture-emulator-launched', output)

    def test_avd_creation_exit_zero_without_exact_avd_never_launches_emulator(self):
        status, output = self.run_boot_fixture('missing_avd')
        self.assertNotEqual(status, 0, output)
        for evidence in ('AVD_CREATION_POSTCONDITION_FAILED', 'pixel',
                         'andy-ci-api36-other', 'synthetic-avd.ini'):
            self.assertIn(evidence, output)
        self.assertNotIn('fixture-emulator-launched', output)

    def test_avd_path_diagnostics_are_bounded_before_emulator_launch(self):
        job = self.jobs(self.workflow())['android-instrumentation']
        boot = self.step(job, 'Create and boot API 36 emulator')
        self.assertIn('echo "HOME=$HOME"', boot)
        start = boot.index('echo "HOME=$HOME"')
        end = boot.index('          "$EMULATOR" \\\n')
        self.assertGreater(start, boot.index('--device "$DEVICE_ID"'))
        diagnostic = boot[start:end]
        for variable in ('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_SDK_HOME',
                         'ANDROID_USER_HOME', 'ANDROID_EMULATOR_HOME', 'ANDROID_AVD_HOME'):
            self.assertIn('echo "' + variable + '=${' + variable + ':-UNSET}"', diagnostic)
        for command in ('"$AVDMANAGER" list avd', '"$AVDMANAGER" list avd -c',
                        '"$EMULATOR" -list-avds'):
            self.assertIn('timeout -k 1s 10s ' + command + ' || true', diagnostic)
        for path in ('"$HOME/.android"', '"$ANDROID_AVD_HOME"', '"$ANDROID_USER_HOME"',
                     '"$ANDROID_EMULATOR_HOME"', '"$ANDROID_SDK_HOME"', '"$HOME" "$SDK_ROOT"'):
            self.assertIn('timeout -k 1s 5s find ' + path, diagnostic)
        for forbidden in ('export ', 'sudo ', 'ln ', 'mv ', 'cp ', 'env\n', 'printenv',
                          '${{ secrets', 'find / '):
            self.assertNotIn(forbidden, diagnostic)

    def test_avd_path_diagnostics_show_divergent_lists_and_block_launch(self):
        status, output = self.run_boot_fixture('emulator_missing', path_environment=True)
        self.assertNotEqual(status, 0, output)
        for evidence in ('=== avdmanager detailed list ===', '=== avdmanager compact list ===',
                         '=== emulator visible avds ===', '=== HOME android AVD files ===',
                         'Path: ', 'andy-ci-api36', 'synthetic-emulator-other-avd',
                         'synthetic-avd.ini'):
            self.assertIn(evidence, output)
        for variable in ('HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_SDK_HOME',
                         'ANDROID_USER_HOME', 'ANDROID_EMULATOR_HOME', 'ANDROID_AVD_HOME'):
            self.assertRegex(output, r'(?m)^' + variable + r'=/.+$')
        self.assertIn('AVD_EMULATOR_DISCOVERY_FAILED', output)
        self.assertNotIn('fixture-emulator-launched', output)

    def test_avd_path_diagnostics_handle_unset_optional_variables(self):
        status, output = self.run_boot_fixture('success')
        self.assertEqual(status, 0, output)
        for variable in ('ANDROID_SDK_HOME', 'ANDROID_USER_HOME', 'ANDROID_EMULATOR_HOME'):
            self.assertIn(variable + '=UNSET', output)

    def test_shared_avd_home_is_exported_before_both_tools(self):
        job = self.jobs(self.workflow())['android-instrumentation']
        boot = self.step(job, 'Create and boot API 36 emulator')
        self.assertIn('AVD_HOME="$RUNNER_TEMP/andy-avd"', boot)
        self.assertIn('mkdir -p "$AVD_HOME"', boot)
        self.assertIn('export ANDROID_AVD_HOME="$AVD_HOME"', boot)
        exported = boot.index('export ANDROID_AVD_HOME="$AVD_HOME"')
        for command in ('"$AVDMANAGER" create avd', '"$AVDMANAGER" list avd',
                        '"$EMULATOR" -list-avds', '          "$EMULATOR" \\\n'):
            self.assertLess(exported, boot.index(command))

    def test_shared_avd_home_is_consumed_by_both_tools(self):
        status, output = self.run_boot_fixture('success')
        self.assertEqual(status, 0, output)
        for evidence in ('manager-shared-home=YES', 'emulator-shared-home=YES',
                         'fixture-emulator-launched'):
            self.assertIn(evidence, output)

    def test_shared_avd_home_missing_files_fail_before_launch(self):
        for mode in ('missing_ini', 'missing_avd_dir'):
            with self.subTest(mode=mode):
                status, output = self.run_boot_fixture(mode)
                self.assertNotEqual(status, 0, output)
                self.assertIn('AVD_FILES_POSTCONDITION_FAILED', output)
                self.assertNotIn('fixture-emulator-launched', output)

    def run_boot_fixture(self, mode, devices='pixel\npixel_xl\n', path_environment=False):
        job = self.jobs(self.workflow())['android-instrumentation']
        step = self.step(job, 'Create and boot API 36 emulator')
        script = textwrap.dedent(step.split('        run: |\n', 1)[1])
        # Exercise the actual Bash with short test-only deadlines; no real SDK/AVD.
        script = script.replace('SECONDS + 240', 'SECONDS + 2')
        script = script.replace('3s ', '0.2s ').replace('60s ', '0.2s ')
        script = script.replace('10s ', '0.2s ').replace('sleep 2', 'sleep 0.05')
        script = script.replace('$HOME', '$RUNNER_TEMP')
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / '.android/avd').mkdir(parents=True)
            (root / '.android/avd/synthetic-avd.ini').touch()
            fixtures = {
                'cmdline-tools/latest/bin/avdmanager':
                    'if [[ "${ANDROID_AVD_HOME:-}" == "$RUNNER_TEMP/andy-avd" ]]; then\n'
                    '  echo manager-shared-home=YES >> "$RUNNER_TEMP/shared-home-evidence"\n'
                    'fi\n'
                    'case "$*" in\n'
                    '  "list device -c") printf "%s" "$FIXTURE_DEVICES" ;;\n'
                    '  "list avd") echo "Path: $RUNNER_TEMP/.android/avd/andy-ci-api36.avd" ;;\n'
                    '  "list avd -c")\n'
                    '    if [[ "$BOOT_FIXTURE" == missing_avd ]]; then echo andy-ci-api36-other;\n'
                    '    elif [[ -f "$RUNNER_TEMP/selected-device" ]]; then echo andy-ci-api36; fi ;;\n'
                    '  "create avd "*)\n'
                    '    if [[ "$BOOT_FIXTURE" == avd_failure ]]; then exit 9; fi\n'
                    '    while (( $# )); do\n'
                    '      if [[ "$1" == --device ]]; then\n'
                    '        printf "selected-device=%s\\n" "$2" > "$RUNNER_TEMP/selected-device"\n'
                    '        if [[ -n "${ANDROID_AVD_HOME:-}" ]]; then\n'
                    '          if [[ "$BOOT_FIXTURE" != missing_ini ]]; then\n'
                    '            touch "$ANDROID_AVD_HOME/andy-ci-api36.ini"\n'
                    '          fi\n'
                    '          if [[ "$BOOT_FIXTURE" != missing_avd_dir ]]; then\n'
                    '            mkdir -p "$ANDROID_AVD_HOME/andy-ci-api36.avd"\n'
                    '          fi\n'
                    '        fi\n'
                    '        exit 0\n'
                    '      fi\n'
                    '      shift\n'
                    '    done ;;\n'
                    '  *) exit 2 ;;\n'
                    'esac\n',
                'emulator/emulator':
                    'if [[ "${ANDROID_AVD_HOME:-}" == "$RUNNER_TEMP/andy-avd" ]]; then\n'
                    '  echo emulator-shared-home=YES >> "$RUNNER_TEMP/shared-home-evidence"\n'
                    'fi\n'
                    'if [[ "$*" == -list-avds ]]; then\n'
                    '  if [[ "$BOOT_FIXTURE" == emulator_missing ]]; then echo synthetic-emulator-other-avd;\n'
                    '  else echo andy-ci-api36; fi\n'
                    '  exit 0\n'
                    'fi\n'
                    'touch "$RUNNER_TEMP/emulator-launched"\n'
                    'echo synthetic-emulator-log\n'
                    'if [[ "$BOOT_FIXTURE" == early_exit ]]; then exit 1; fi\n'
                    'exec sleep 30\n',
                'platform-tools/adb':
                    'case "$*" in\n'
                    '  wait-for-device)\n'
                    '    if [[ "$BOOT_FIXTURE" == success || "$BOOT_FIXTURE" == boot_stall ]]; '
                    'then exit 0; fi\n'
                    '    exec sleep 30 ;;\n'
                    '  "shell getprop sys.boot_completed")\n'
                    '    if [[ "$BOOT_FIXTURE" == success ]]; then echo 1; exit 0; fi\n'
                    '    exec sleep 30 ;;\n'
                    '  "devices -l")\n'
                    '    if [[ "$BOOT_FIXTURE" == diagnostics_stall ]]; then exec sleep 30; fi\n'
                    '    echo synthetic-adb-devices ;;\n'
                    '  *) exit 2 ;;\n'
                    'esac\n',
            }
            for relative, content in fixtures.items():
                executable = root / relative
                executable.parent.mkdir(parents=True, exist_ok=True)
                executable.write_text('#!/bin/bash\n' + content)
                executable.chmod(0o755)
            env = dict(os.environ, ANDROID_SDK_ROOT=temporary, ANDROID_HOME=temporary,
                       RUNNER_TEMP=temporary, BOOT_FIXTURE=mode, FIXTURE_DEVICES=devices)
            for variable in ('ANDROID_SDK_HOME', 'ANDROID_USER_HOME', 'ANDROID_EMULATOR_HOME',
                             'ANDROID_AVD_HOME'):
                env.pop(variable, None)
                if path_environment:
                    env[variable] = str(root / '.android/avd')
            process = subprocess.Popen(['bash', '-c', script], env=env, cwd=temporary,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, start_new_session=True)
            try:
                try:
                    output, _ = process.communicate(timeout=6)
                except subprocess.TimeoutExpired:
                    self.fail('Boot script exceeded diagnostic fixture deadline: ' + mode)
                if (root / 'selected-device').exists():
                    output += (root / 'selected-device').read_text()
                if (root / 'emulator-launched').exists():
                    output += 'fixture-emulator-launched\n'
                if (root / 'shared-home-evidence').exists():
                    output += (root / 'shared-home-evidence').read_text()
                return process.returncode, output
            finally:
                # Kill only the synthetic process group, including its fake emulator.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate()

    def test_emulator_boot_failures_are_bounded_and_surface_diagnostics(self):
        for mode in ('early_exit', 'adb_stall', 'boot_stall', 'diagnostics_stall', 'avd_failure'):
            with self.subTest(mode=mode):
                status, output = self.run_boot_fixture(mode)
                self.assertNotEqual(status, 0, output)
                for heading in ('=== emulator process ===', '=== adb devices ===',
                                '=== emulator log ===', '=== host virtualization ==='):
                    self.assertIn(heading, output)
                if mode != 'avd_failure':
                    self.assertIn('synthetic-emulator-log', output)
                if mode == 'early_exit':
                    self.assertIn('EMULATOR_EXITED_BEFORE_BOOT', output)
                    self.assertNotIn('EMULATOR_BOOT_TIMEOUT', output)

    def test_emulator_boot_success_requires_boot_completed(self):
        status, output = self.run_boot_fixture('success')
        self.assertEqual(status, 0, output)
        self.assertNotIn('=== emulator log ===', output)

    def test_android_instrumentation_rejects_privilege_and_emulator_mutations(self):
        text = self.workflow()
        self.assert_android_instrumentation_job(text)
        job = self.jobs(text)['android-instrumentation']
        mutations = [
            job.replace('contents: read', 'contents: write'),
            job.replace('persist-credentials: false', 'persist-credentials: true', 1),
            job.replace('github.event.pull_request.head.sha', 'github.head_ref'),
            job.replace("'system-images;android-36;google_apis;x86_64'",
                        "'system-images;android-35;google_apis;x86_64'"),
            job.replace(':data:device-identity:connectedDebugAndroidTest', ':app:assembleDebug'),
            job.replace('SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"',
                        'SDK_ROOT="/usr/local/lib/android/sdk"', 1),
            job + '\n      - run: sudo true\n',
            job + '\n      - run: ssh synthetic.invalid\n',
        ]
        for index, mutation in enumerate(mutations):
            candidate = text.replace(job, mutation, 1)
            with self.subTest(mutation=index), self.assertRaises(AssertionError):
                self.assert_android_instrumentation_job(candidate)

    def assert_android_instrumentation_job(self, text):
        jobs = self.jobs(text)
        self.assertIn('android-instrumentation', jobs)
        job = jobs['android-instrumentation']
        self.assertIn('name: android-instrumentation', job)
        self.assertIn('runs-on: ubuntu-latest', job)
        self.assertIn('timeout-minutes: 35', job)
        permissions = re.search(r'^    permissions:\n(.*?)^    steps:', job,
                                flags=re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(permissions)
        self.assertEqual(permissions[1].strip(), 'contents: read')
        checkouts = self.checkouts(job)
        self.assertEqual(len(checkouts), 2)
        self.assertIn('ref: ${{ github.sha }}', checkouts[0])
        self.assertIn('ref: ${{ github.event.pull_request.head.sha }}', checkouts[1])
        for checkout in checkouts:
            self.assertEqual(checkout.count('persist-credentials: false'), 1)
            self.assertIn('fetch-depth: 1', checkout)
        self.assertIn("if: github.event_name == 'push'", checkouts[0])
        self.assertIn("if: github.event_name == 'pull_request_target'", checkouts[1])
        self.assertIn("java-version: '17'", job)
        self.assertIn('distribution: temurin', job)
        self.assertNotRegex(job, r'\$\{\{\s*secrets\b|:\s*write(?:-all)?\b')
        detection = self.step(job, 'Detect device identity module')
        self.assertIn('[[ -f data/device-identity/build.gradle.kts ]]', detection)
        self.assertIn('DEVICE_IDENTITY_MODULE_NOT_PRESENT', detection)
        for name in ('Install Android emulator dependencies', 'Create and boot API 36 emulator',
                     'Run real Android Keystore instrumentation tests'):
            self.assert_step_condition(self.step(job, name),
                                       "steps.device_identity.outputs.present == 'true'")
        self.assert_step_condition(self.step(job, 'Stop emulator'),
                                   "always() && steps.device_identity.outputs.present == 'true'")
        for required in (
            'data/device-identity/build.gradle.kts',
            'DEVICE_IDENTITY_MODULE_PRESENT',
            'SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"',
            'cmdline-tools/latest/bin/sdkmanager',
            'cmdline-tools/latest/bin/avdmanager',
            "'platforms;android-37.0'",
            "'build-tools;36.0.0'",
            "'system-images;android-36;google_apis;x86_64'",
            'andy-ci-api36',
            'sys.boot_completed',
            ':data:device-identity:connectedDebugAndroidTest',
            'emu kill',
        ):
            self.assertIn(required, job)
        for forbidden in (
            'secrets.', 'contents: write', 'id-token:', 'apt-get ',
            'setup-android', 'reactivecircus', 'self-hosted', 'ssh ', 'note',
            '/usr/local/lib/android/sdk', 'GITHUB_PATH', 'export PATH=',
            'continue-on-error:', 'deploy', 'publish', 'signing',
        ):
            self.assertNotIn(forbidden, job)

        self.assert_kvm_preflight(text)

    def test_android_instrumentation_has_separate_unprivileged_boundary(self):
        self.assert_android_instrumentation_job(self.workflow())

    def test_device_identity_frontier_rejects_unlisted_paths(self):
        manifest = device_identity_manifest()
        errors = evaluate_frontier(manifest, ['README.md'], {})
        self.assertIn('ARCH_GOVERNANCE_MUTATION:README.md', errors)

    def test_device_identity_frontier_is_exact(self):
        manifest = device_identity_manifest()
        self.assertEqual(manifest['schema_version'], 1)
        self.assertEqual(manifest['frontier_id'], 'android-device-identity-v1')
        self.assertEqual(manifest['branch'], 'feat/android-device-identity-v1')
        self.assertEqual(set(manifest['allowed_paths']), DEVICE_IDENTITY_ALLOWED_PATHS)
        self.assertEqual(set(manifest['required_artifacts']), DEVICE_IDENTITY_ALLOWED_PATHS)
        for forbidden in (
            'retrofit', 'okhttp', 'ktor-client', 'dagger', 'hilt',
            'androidx\\.room', 'firebase', 'com\\.google\\.android\\.gms',
            'androidx\\.work', 'play-services-location', 'home.?assistant',
            'whatsapp', 'android\\.permission\\.INTERNET',
        ):
            self.assertIn(forbidden, manifest['forbidden_content_patterns'])
        self.assertEqual(validate_manifest(manifest), [])

    def test_human_identity_frontier_is_exact(self):
        manifest = human_identity_manifest()
        self.assertEqual(manifest['schema_version'], 1)
        self.assertEqual(manifest['frontier_id'], 'android-human-identity-v1')
        self.assertEqual(manifest['branch'], 'feat/android-human-identity-v1')
        self.assertEqual(set(manifest['allowed_paths']), HUMAN_IDENTITY_ALLOWED_PATHS)
        self.assertEqual(set(manifest['required_artifacts']), HUMAN_IDENTITY_ALLOWED_PATHS)
        self.assertEqual(validate_manifest(manifest), [])

    def test_human_identity_frontier_permits_internet_and_required_clients(self):
        manifest = human_identity_manifest()
        candidates = {
            'app/src/main/AndroidManifest.xml':
                '<uses-permission android:name="android.permission.INTERNET" />',
            'app/build.gradle.kts': 'implementation("androidx.credentials:credentials:1.0.0")',
            'integrations/google-identity/build.gradle.kts':
                'implementation("com.google.android.libraries.identity.googleid:googleid:1.0.0")',
            'sdk/client-api/build.gradle.kts': 'implementation("com.squareup.okhttp3:okhttp:1.0.0")',
        }
        self.assertEqual(evaluate_frontier(manifest, list(candidates), candidates), [])

    def test_human_identity_frontier_blocks_disallowed_permissions(self):
        manifest = human_identity_manifest()
        path = 'app/src/main/AndroidManifest.xml'
        for permission in (
            'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'ACCESS_BACKGROUND_LOCATION',
            'POST_NOTIFICATIONS', 'CAMERA', 'RECORD_AUDIO', 'READ_CONTACTS', 'READ_SMS',
            'RECEIVE_SMS', 'BLUETOOTH_CONNECT', 'BLUETOOTH_SCAN',
        ):
            with self.subTest(permission=permission):
                errors = evaluate_frontier(
                    manifest, [path], {path: 'android.permission.' + permission})
                self.assertTrue(any(error.startswith('ARCH_DEPENDENCY_FORBIDDEN:')
                                    for error in errors), errors)

    def test_human_identity_frontier_blocks_prohibited_dependencies(self):
        manifest = human_identity_manifest()
        path = 'app/build.gradle.kts'
        for dependency in (
            'retrofit', 'ktor-client-okhttp', 'dagger', 'hilt', 'androidx.room', 'firebase',
            'androidx.work', 'play-services-location', 'com.google.android.gms.location',
            'home-assistant', 'whatsapp',
        ):
            with self.subTest(dependency=dependency):
                errors = evaluate_frontier(manifest, [path], {path: dependency})
                self.assertTrue(any(error.startswith('ARCH_DEPENDENCY_FORBIDDEN:')
                                    for error in errors), errors)

    def test_human_identity_frontier_cannot_mutate_its_own_manifest(self):
        manifest = human_identity_manifest()
        path = '.github/architecture/frontiers/android-human-identity-v1.json'
        errors = evaluate_frontier(manifest, [path], {})
        self.assertIn('ARCH_GOVERNANCE_MUTATION:' + path, errors)
        self.assertIn('ARCH_PATH_NOT_ALLOWED:' + path, errors)

    def test_human_identity_frontier_fails_closed_outside_its_scope(self):
        manifest = human_identity_manifest()
        for path in (
            'core/device-identity/build.gradle.kts',
            'data/device-identity/build.gradle.kts',
            'README.md',
        ):
            with self.subTest(path=path):
                errors = evaluate_frontier(manifest, [path], {})
                self.assertIn('ARCH_PATH_NOT_ALLOWED:' + path, errors)

    def workflow(self):
        return (Path(__file__).resolve().parents[2] / '.github/workflows/governance.yml').read_text()

    def jobs(self, text):
        sections = re.split(r'^  ([a-z][a-z-]*):\s*$', text.split('\njobs:\n', 1)[1],
                            flags=re.MULTILINE)
        names = sections[1::2]
        self.assertEqual(len(names), len(set(names)))
        return dict(zip(names, sections[2::2]))

    def checkouts(self, job):
        steps = re.split(r'^      - ', job, flags=re.MULTILINE)[1:]
        return [step for step in steps if 'uses: actions/checkout@' in step]

    def step(self, job, name):
        steps = re.split(r'^      - ', job, flags=re.MULTILINE)[1:]
        matches = [step for step in steps if step.startswith('name: ' + name + '\n')]
        self.assertEqual(len(matches), 1, name)
        return matches[0]

    def assert_step_condition(self, step, condition):
        self.assertEqual(re.findall(r'^        if: (.*)$', step, flags=re.MULTILINE),
                         [condition])

    def assert_trusted_jobs(self, text):
        self.assertIn('  pull_request_target:', text)
        self.assertNotIn('  pull_request:', text)
        self.assertIn('permissions:\n  contents: read', text)
        self.assertNotRegex(text, r'\$\{\{\s*secrets\b')
        self.assertNotRegex(text, r':\s*write(?:-all)?\b')
        jobs = self.jobs(text)
        for name in ('governance-tests', 'secret-scan', 'architecture-guard'):
            job = jobs[name]
            self.assertIn('name: ' + name, job)
            checkouts = self.checkouts(job)
            self.assertEqual(len(checkouts), 1)
            self.assertIn('ref: ${{ github.event.pull_request.base.sha || github.sha }}',
                          checkouts[0])
            self.assertEqual(checkouts[0].count('persist-credentials: false'), 1)
            for forbidden in ('ref: ${{ github.event.pull_request.head.sha }}',
                              'git checkout', 'git switch', 'gradlew', 'eval ',
                              'pip install', 'head.repo', 'BASH_ENV', 'PYTHONPATH'):
                self.assertNotIn(forbidden, job)
        for name in ('secret-scan', 'architecture-guard'):
            job = jobs[name]
            self.assertIn('test "$ACTUAL_HEAD_SHA" = "$HEAD_SHA"', job)
            self.assertIn('"pull/${PR_NUMBER}/head:refs/remotes/origin/guard-candidate"', job)
        self.assertIn('python -m unittest discover -s tests -p', jobs['governance-tests'])
        self.assertIn('python -I scripts/security/scan_secrets.py --git-ref "$HEAD_SHA"',
                      jobs['secret-scan'])
        self.assertIn('python -I scripts/architecture/check_guardrails.py --base-ref "$BASE_SHA" '
                      '--head-ref "$HEAD_SHA" --branch "$HEAD_BRANCH"', jobs['architecture-guard'])

    def assert_android_build_job(self, text):
        jobs = self.jobs(text)
        self.assertIn('android-build', jobs)
        job = jobs['android-build']
        self.assertIn('name: android-build', job)
        self.assertIn('runs-on: ubuntu-latest', job)
        self.assertIn('timeout-minutes: 25', job)
        permissions = re.search(r'^    permissions:\n(.*?)^    steps:', job,
                                flags=re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(permissions)
        self.assertEqual(permissions[1].strip(), 'contents: read')
        checkouts = self.checkouts(job)
        self.assertEqual(len(checkouts), 2)
        for checkout, event, ref in zip(checkouts, ('push', 'pull_request_target'),
                                        ('github.sha', 'github.event.pull_request.head.sha')):
            self.assertIn("if: github.event_name == '" + event + "'", checkout)
            self.assertIn('ref: ${{ ' + ref + ' }}', checkout)
            self.assertEqual(checkout.count('persist-credentials: false'), 1)
        self.assertIn('uses: actions/setup-java@v4', job)
        self.assertIn('distribution: temurin', job)
        self.assertIn("java-version: '17'", job)
        self.assertIn('[[ -x ./gradlew && -f ./settings.gradle.kts && -f ./app/build.gradle.kts ]]', job)
        self.assertIn('ANDROID_PROJECT_NOT_PRESENT', job)
        for required in ('SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"',
                         'test -n "$SDK_ROOT"',
                         'SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"',
                         'test -x "$SDKMANAGER"',
                         '"$SDKMANAGER" --version',
                         '"$SDKMANAGER" \'platforms;android-37.0\' \'build-tools;36.0.0\'',
                         'test -d "$SDK_ROOT/platforms/android-37.0"',
                         'test -d "$SDK_ROOT/build-tools/36.0.0"'):
            self.assertIn(required, job)
        self.assertNotIn('command -v sdkmanager', job)
        self.assertNotRegex(job, r'(?m)^\s*sdkmanager\b')
        for forbidden in ('/usr/local/lib/android/sdk', 'GITHUB_PATH', 'sudo ',
                          'apt-get ', 'setup-android', 'export PATH='):
            self.assertNotIn(forbidden, job)
        for name in ('Install compile SDK and build tools', 'Build and test exact candidate',
                     'Detect device identity module'):
            self.assert_step_condition(self.step(job, name),
                                       "steps.android_project.outputs.present == 'true'")
        detection = self.step(job, 'Detect device identity module')
        self.assertIn('[[ -f core/device-identity/build.gradle.kts && '
                      '-f data/device-identity/build.gradle.kts ]]', detection)
        self.assertIn('DEVICE_IDENTITY_MODULE_PRESENT', detection)
        self.assertIn('DEVICE_IDENTITY_MODULE_NOT_PRESENT', detection)
        human_detection = self.step(job, 'Detect human identity modules')
        self.assertEqual(re.findall(r'^        if: .*$', human_detection, flags=re.MULTILINE), [])
        for module in ('core/human-identity/build.gradle.kts',
                       'sdk/client-api/build.gradle.kts',
                       'integrations/google-identity/build.gradle.kts',
                       'features/onboarding/build.gradle.kts'):
            self.assertIn('-f ' + module, human_detection)
        self.assertEqual(human_detection.count('&&'), 3)
        self.assertIn("echo 'present=true' >> \"$GITHUB_OUTPUT\"", human_detection)
        self.assertIn('HUMAN_IDENTITY_MODULES_PRESENT', human_detection)
        self.assertIn("echo 'present=false' >> \"$GITHUB_OUTPUT\"", human_detection)
        self.assertIn('HUMAN_IDENTITY_MODULES_NOT_PRESENT', human_detection)
        jvm_tests = self.step(job, 'Run device identity JVM tests')
        self.assert_step_condition(jvm_tests,
                                   "steps.device_identity.outputs.present == 'true'")
        self.assertIn(':core:device-identity:test', jvm_tests)
        self.assertIn(':data:device-identity:testDebugUnitTest', jvm_tests)
        human_jvm_tests = self.step(job, 'Run human identity JVM tests')
        self.assert_step_condition(human_jvm_tests,
                                   "steps.human_identity.outputs.present == 'true'")
        for task in (':core:human-identity:test', ':sdk:client-api:test',
                     ':integrations:google-identity:testDebugUnitTest',
                     ':features:onboarding:testDebugUnitTest'):
            self.assertIn(task, human_jvm_tests)
        self.assertIn('./gradlew --no-daemon', human_jvm_tests)
        self.assertLess(job.index(self.step(job, 'Build and test exact candidate')),
                        job.index(jvm_tests))
        self.assertLess(job.index(jvm_tests), job.index(human_jvm_tests))
        self.assertIn('./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug', job)
        steps = re.split(r'^      - ', job, flags=re.MULTILINE)[1:]
        uploads = [step for step in steps if 'uses: actions/upload-artifact@' in step]
        self.assertEqual(len(uploads), 1)
        upload = uploads[0]
        self.assertIn('uses: actions/upload-artifact@v4', upload)
        self.assert_step_condition(upload,
                                   "success() && steps.android_project.outputs.present == 'true'")
        self.assertLess(job.index(jvm_tests), job.index(upload))
        self.assertEqual(upload.split('        with:\n', 1)[1].strip(),
                         'name: andy-debug-apk\n'
                         '          path: app/build/outputs/apk/debug/app-debug.apk\n'
                         '          retention-days: 7\n'
                         '          if-no-files-found: error')
        self.assertLess(job.index('./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug'),
                        job.index(upload))
        self.assertNotIn('continue-on-error:', job)
        self.assertNotRegex(job, r'\$\{\{\s*secrets\b|:\s*write(?:-all)?\b')
        for forbidden in ('GH_TOKEN', 'GITHUB_TOKEN', 'signing', 'deploy', 'publish',
                          'id-token', 'ssh', 'note', 'self-hosted', 'attention-router',
                          'BASH_ENV', 'PYTHONPATH', 'environment:', 'env:', 'cache:',
                          'GOOGLE_', 'google account', 'gcloud ', 'curl ', 'wget '):
            self.assertNotIn(forbidden, job)

    def test_workflow_keeps_security_jobs_base_trusted(self):
        self.assert_trusted_jobs(self.workflow())

    def test_android_build_has_separate_unprivileged_boundary(self):
        self.assert_android_build_job(self.workflow())

    def test_human_identity_detection_requires_all_modules_and_allows_absence(self):
        text = self.workflow()
        self.assert_android_build_job(text)
        job = self.jobs(text)['android-build']
        for module in ('core/human-identity/build.gradle.kts',
                       'sdk/client-api/build.gradle.kts',
                       'integrations/google-identity/build.gradle.kts',
                       'features/onboarding/build.gradle.kts'):
            with self.subTest(module=module), self.assertRaises(AssertionError):
                self.assert_android_build_job(text.replace(module, 'missing/' + module, 1))
        detection = self.step(job, 'Detect human identity modules')
        self.assertIn("echo 'present=false' >> \"$GITHUB_OUTPUT\"", detection)
        self.assertIn('HUMAN_IDENTITY_MODULES_NOT_PRESENT', detection)

    def test_human_identity_tests_run_only_in_unprivileged_android_build(self):
        text = self.workflow()
        tasks = (':core:human-identity:test', ':sdk:client-api:test',
                 ':integrations:google-identity:testDebugUnitTest',
                 ':features:onboarding:testDebugUnitTest')
        android_build = self.jobs(text)['android-build']
        for task in tasks:
            self.assertIn(task, android_build)
            for trusted_job in ('governance-tests', 'secret-scan', 'architecture-guard'):
                self.assertNotIn(task, self.jobs(text)[trusted_job])

    def test_trusted_boundary_rejects_candidate_execution_mutations(self):
        text = self.workflow()
        mutations = [
            text.replace('github.event.pull_request.base.sha || github.sha',
                         'github.event.pull_request.head.sha', 1),
            text.replace('persist-credentials: false', 'persist-credentials: true', 1),
            text.replace('run: python -m unittest', 'run: ./gradlew # python -m unittest', 1),
            text.replace('contents: read', 'contents: write', 1),
            text.replace('python -I scripts/security/scan_secrets.py --git-ref "$HEAD_SHA"',
                         'python candidate/scripts/security/scan_secrets.py'),
            text.replace('test "$ACTUAL_HEAD_SHA" = "$HEAD_SHA"', 'true', 1),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutations.index(mutation)), self.assertRaises(AssertionError):
                self.assert_trusted_jobs(mutation)

    def test_android_build_rejects_privilege_and_checkout_mutations(self):
        text = self.workflow()
        self.assert_android_build_job(text)
        prefix, job = text.split('\n  android-build:', 1)
        mutations = [
            job.replace('github.event.pull_request.head.sha', 'github.head_ref'),
            job.replace('persist-credentials: false', 'persist-credentials: true', 1),
            job.replace('contents: read', 'contents: write'),
            job.replace('contents: read', 'contents: read\n      packages: read'),
            job + '\n        env:\n          TOKEN: ${{ secrets.SYNTHETIC }}\n',
            job + '\n      - run: ssh synthetic.invalid\n',
            job.replace(':app:assembleDebug', ':app:publish'),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutations.index(mutation)), self.assertRaises(AssertionError):
                self.assert_android_build_job(prefix + '\n  android-build:' + mutation)

    def test_android_build_rejects_sdk_and_artifact_mutations(self):
        text = self.workflow()
        self.assert_android_build_job(text)
        mutations = [
            text.replace("'platforms;android-37.0'", "'platforms;android-37'"),
            text.replace('actions/upload-artifact@v4', 'actions/download-artifact@v4'),
            text.replace("success() && steps.android_project.outputs.present == 'true'",
                         'always()'),
            text.replace("success() && steps.android_project.outputs.present == 'true'",
                         'success()'),
            text.replace('path: app/build/outputs/apk/debug/app-debug.apk', 'path: app/**'),
            text.replace('name: andy-debug-apk', 'name: other-artifact'),
            text.replace('retention-days: 7', 'retention-days: 90'),
            text.replace('if-no-files-found: error', 'if-no-files-found: warn'),
            text.replace('name: Build and test exact candidate',
                         'name: Build and test exact candidate\n        continue-on-error: true'),
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=index), self.assertRaises(AssertionError):
                self.assert_android_build_job(mutation)

    def test_android_build_rejects_sdkmanager_path_mutations(self):
        text = self.workflow()
        self.assert_android_build_job(text)
        mutations = [
            text.replace('SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"',
                         'SDK_ROOT="${ANDROID_SDK_ROOT:-}"'),
            text.replace('cmdline-tools/latest/bin/sdkmanager', 'tools/bin/sdkmanager'),
            text.replace('test -n "$SDK_ROOT"', 'true'),
            text.replace('test -x "$SDKMANAGER"', 'command -v sdkmanager'),
            text.replace('"$SDKMANAGER" --version', 'sdkmanager --version'),
            text.replace('"$SDKMANAGER" \'platforms;', 'sdkmanager \'platforms;'),
            text.replace('test -d "$SDK_ROOT/platforms/android-37.0"', 'true'),
            text.replace('test -d "$SDK_ROOT/build-tools/36.0.0"', 'true'),
            text + '\n          command -v sdkmanager\n',
            text + '\n          sdkmanager --version\n',
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=index), self.assertRaises(AssertionError):
                self.assert_android_build_job(mutation)

    def test_android_build_rejects_weakened_step_conditions(self):
        text = self.workflow()
        self.assert_android_build_job(text)
        job = self.jobs(text)['android-build']
        for name in ('Install compile SDK and build tools', 'Build and test exact candidate',
                     'Detect device identity module', 'Run device identity JVM tests',
                     'Run human identity JVM tests', 'Upload debug APK'):
            step = self.step(job, name)
            for replacement in ('', '        if: always()'):
                weakened = re.sub(r'^        if: .*$', replacement, step, flags=re.MULTILINE)
                with self.subTest(step=name, condition=replacement), self.assertRaises(AssertionError):
                    self.assert_android_build_job(text.replace(step, weakened, 1))

    def test_android_build_rejects_incomplete_device_identity_jvm_gate(self):
        text = self.workflow()
        self.assert_android_build_job(text)
        job = self.jobs(text)['android-build']
        mutations = [
            job.replace(' && -f data/device-identity/build.gradle.kts', ''),
            job.replace(':core:device-identity:test', ':app:testDebugUnitTest'),
            job.replace(':data:device-identity:testDebugUnitTest', ':app:testDebugUnitTest'),
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=index), self.assertRaises(AssertionError):
                self.assert_android_build_job(text.replace(job, mutation, 1))

    def test_exact_allowed_path_passes(self):
        self.assertEqual(evaluate_frontier(policy(), ['settings.gradle.kts'], {}), [])

    def test_path_outside_allowlist_fails(self):
        self.assertIn('ARCH_PATH_NOT_ALLOWED:README.md',
                      evaluate_frontier(policy(), ['README.md'], {}))

    def test_governance_mutation(self):
        for path in ['README.md', 'SECURITY.md', 'app/AGENTS.md',
                     '.github/workflows/governance.yml',
                     'scripts/security/scan_secrets.py',
                     'scripts/architecture/check_guardrails.py',
                     'docs/architecture/README.md']:
            with self.subTest(path=path):
                self.assertIn('ARCH_GOVERNANCE_MUTATION:' + path,
                              evaluate_frontier(policy(), [path], {}))

    def test_forbidden_dependency(self):
        errors = evaluate_frontier(policy(), ['settings.gradle.kts'],
                                   {'settings.gradle.kts': 'RETROFIT'})
        self.assertTrue(any(e.startswith('ARCH_DEPENDENCY_FORBIDDEN:') for e in errors))

    def test_invalid_manifests(self):
        cases = [{}, [], None]
        for key in policy():
            p = policy()
            del p[key]
            cases.append(p)
        for key, value in [('schema_version', True), ('allowed_paths', ['../escape']),
                           ('allowed_paths', ['/absolute']), ('allowed_paths', ['a/./b']),
                           ('allowed_paths', ['a//b']), ('allowed_paths', ['a\\b']),
                           ('allowed_paths', ['a', 'a']), ('allowed_paths', ['AGENTS.md']),
                           ('required_artifacts', ['outside']),
                           ('forbidden_content_patterns', ['[']), ('branch', 'docs/spoof'),
                           ('frontier_id', ''), ('allowed_paths', ['*.kt'])]:
            p = copy.deepcopy(policy())
            p[key] = value
            cases.append(p)
        for p in cases:
            with self.subTest(policy=p):
                self.assertTrue(any(e.startswith('ARCH_POLICY_INVALID')
                                    for e in validate_manifest(p)))

    def test_candidate_traversal(self):
        for path in ['../outside', '/outside', 'a/../b', 'a\\b', 'a//b']:
            self.assertTrue(evaluate_frontier(policy(), [path], {}))


if __name__ == '__main__':
    unittest.main()
