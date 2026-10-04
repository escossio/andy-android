"""Presence policy regression fixtures; Android candidate text is never executed."""
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

from scripts.architecture.check_guardrails import evaluate_frontier, validate_manifest

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = '.github/architecture/frontiers/android-presence-stage-v1.json'
MODULE = 'features/presence/'
SOURCE = MODULE + 'src/main/java/io/github/escossio/andy/features/presence/'
TEST = MODULE + 'src/test/java/io/github/escossio/andy/features/presence/'
PACKAGE = 'package io.github.escossio.andy.features.presence\n'
BUILD = 'android { namespace = "io.github.escossio.andy.features.presence" }\n'
RENDERER = SOURCE + 'SceneViewPresenceRenderer.kt'


class PresenceStagePolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = json.loads((ROOT / MANIFEST).read_text())

    def errors(self, text, path=RENDERER):
        return evaluate_frontier(self.policy, [path], {path: text})

    def test_exact_path_budget_and_required_artifacts(self):
        expected = {
            'settings.gradle.kts', 'gradle/libs.versions.toml',
            'features/onboarding/build.gradle.kts',
            'features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/OnboardingScreen.kt',
            MODULE + 'build.gradle.kts', MODULE + 'src/main/AndroidManifest.xml',
            SOURCE + 'PresenceStage.kt', SOURCE + 'PresenceReplay.kt', RENDERER,
            MODULE + 'src/main/assets/presence/andy.glb',
            MODULE + 'src/main/assets/presence/andy.LICENSE.txt',
            TEST + 'PresenceReplayTest.kt', TEST + 'PresenceStageTest.kt',
        }
        self.assertEqual(validate_manifest(self.policy), [])
        self.assertEqual(self.policy['branch'], 'feat/android-presence-stage-v1')
        self.assertEqual(set(self.policy['allowed_paths']), expected)
        self.assertEqual(set(self.policy['required_artifacts']), expected)

    def test_unchanged_integration_text_is_not_accidentally_forbidden(self):
        self.check_integration_blobs(ROOT)

    def check_integration_blobs(self, root):
        for path in self.policy['allowed_paths']:
            target = root / path
            try:
                mode = target.lstat().st_mode
            except FileNotFoundError:
                continue
            self.assertTrue(stat.S_ISREG(mode), 'non_regular_entry:' + path)
            raw = target.read_bytes()
            text = {}
            # Match candidate_text_from_ref: NUL or invalid UTF-8 means inert data.
            if b'\0' not in raw:
                try:
                    text[path] = raw.decode('utf-8')
                except UnicodeDecodeError:
                    pass
            self.assertEqual(evaluate_frontier(self.policy, [path], text), [], path)

    def test_allowed_glb_binary_is_inert_in_integration_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / MODULE / 'src/main/assets/presence/andy.glb'
            target.parent.mkdir(parents=True)
            marker = root / 'executed'
            payload = ('from pathlib import Path\nPath(' + repr(str(marker))
                       + ').touch()\n# CameraX\n').encode('utf-8')
            for prefix in (b'glTF\0\xff', b'glTF\0', b'glTF\xff'):
                with self.subTest(prefix=prefix):
                    target.write_bytes(prefix + payload)
                    self.check_integration_blobs(root)
                    self.assertFalse(marker.exists())
            # A .glb suffix alone does not exempt valid UTF-8 from text checks.
            target.write_bytes(payload)
            with self.assertRaisesRegex(AssertionError, 'ARCH_DEPENDENCY_FORBIDDEN'):
                self.check_integration_blobs(root)

    def test_integration_check_rejects_non_regular_glb(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / MODULE / 'src/main/assets/presence/andy.glb'
            target.parent.mkdir(parents=True)
            source = root / 'synthetic.bin'
            source.write_bytes(b'glTF\0\xff')
            target.symlink_to(source)
            with self.assertRaisesRegex(AssertionError, 'non_regular_entry'):
                self.check_integration_blobs(root)
            target.unlink()
            target.mkdir()
            with self.assertRaisesRegex(AssertionError, 'non_regular_entry'):
                self.check_integration_blobs(root)

    def test_rendering_synthetic_replay_and_local_license_are_allowed(self):
        for text in (
            PACKAGE + 'import io.github.sceneview.Scene\nimport com.google.android.filament.Engine',
            PACKAGE + 'data class PresenceFrame(val timeMillis: Long, val seed: Int, val jaw: Float)',
            PACKAGE + 'val pose = "idle_seated"; val clothing = 1; val blink = 0.5f',
            PACKAGE + 'val cameraNode = "virtual scene camera"',
        ):
            self.assertEqual(self.errors(text), [])
        self.assertEqual(self.errors('Synthetic asset license: https://example.invalid/license',
                                    MODULE + 'src/main/assets/presence/andy.LICENSE.txt'), [])
        self.assertEqual(self.errors('<manifest xmlns:android="http://schemas.android.com/apk/res/android" />',
                                    MODULE + 'src/main/AndroidManifest.xml'), [])

    def test_capture_voice_perception_network_and_database_are_rejected(self):
        for text in (
            'CameraX', 'androidx.camera.core', 'android.hardware.camera2.CameraDevice',
            'android.hardware.Camera', 'ImageReader', 'android.permission.CAMERA',
            'RECORD_AUDIO', 'AudioRecord', 'MediaRecorder', 'android.speech.tts.TextToSpeech',
            'SpeechRecognizer', 'android.media.AudioManager', 'FaceDetector',
            'ML Kit', 'com.google.mlkit.vision', 'MediaPipe', 'face_tracking',
            'com.google.ar.core', 'ARSceneView', 'androidx.xr',
            'retrofit2.Retrofit', 'okhttp3.OkHttpClient', 'io.ktor.client', 'ktor-client',
            'java.net.URL', 'javax.net.ssl', 'android.net.ConnectivityManager',
            'HttpURLConnection', 'WebView', 'com.github.kittinunf.fuel.Fuel',
            'val uri = "https://example.invalid/asset.glb"',
            'androidx.room', 'androidx.datastore', 'android.database.sqlite',
            'Firebase', 'postgres', 'dagger', 'hilt', 'androidx.work',
        ):
            with self.subTest(text=text):
                self.assertTrue(self.errors(PACKAGE + text))

    def test_operational_authority_is_rejected_inside_presence(self):
        for text in (
            'import io.github.escossio.andy.sdk.clientapi.ClientSessionStore',
            'import io.github.escossio.andy.features.onboarding.*',
            'import io.github.escossio.andy.features.approvals.ApprovalPanel',
            'import io.github.escossio.andy.data.clientsession.Store',
            'import io.github.escossio.andy.core.humanidentity.HumanIdentity',
            'val client: AttentionRouterClient', 'val session: ClientSession',
            'val credential: String', 'val token: String', 'val approval: String',
            'val onExecute: () -> Unit', 'val onApprove: () -> Unit',
            'val opaque: Any)', 'val service: Context)', 'val callback: Function0<Unit>',
            'SensorManager', 'cameraPermissionGranted', 'onRequestCameraPermission',
        ):
            with self.subTest(text=text):
                self.assertTrue(self.errors(PACKAGE + text))

    def test_module_has_only_rendering_ui_and_test_dependencies(self):
        path = MODULE + 'build.gradle.kts'
        accepted = [
            'implementation(platform(libs.androidx.compose.bom))',
            'implementation(libs.androidx.compose.foundation)',
            'implementation(libs.kotlinx.coroutines.core)',
            'testImplementation(libs.junit)',
            'implementation(libs.sceneview) {\n    exclude(group = "com.github.kittinunf.fuel")\n}',
        ]
        self.assertEqual(self.errors(BUILD + '\n'.join(accepted), path), [])
        for dependency in (
            'implementation(libs.sceneview)',
            'implementation(libs.sceneview) { exclude(group = "synthetic.other") }',
            'implementation(project(":sdk:client-api"))',
            'implementation(project(":features:onboarding"))',
            'implementation(libs.synthetic.backend)',
            'implementation("synthetic:backend:1.0")',
            'implementation(files("backend.jar"))',
            'api(libs.sceneview)', 'runtimeOnly(libs.synthetic.backend)',
            'testImplementation(libs.synthetic.backend)',
        ):
            with self.subTest(dependency=dependency):
                self.assertTrue(self.errors(BUILD + dependency, path))

    def test_no_extra_paths_or_fallback_edits(self):
        for path in (
            'app/src/main/AndroidManifest.xml', 'app/build.gradle.kts',
            'app/src/main/java/io/github/escossio/andy/MainActivity.kt',
            'features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/AndyPresenceAvatar.kt',
            'features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinator.kt',
            MODULE + 'src/main/assets/presence/extra.glb', 'STATUS.md', MANIFEST,
            'features/AGENTS.md', 'scripts/architecture/check_guardrails.py',
        ):
            with self.subTest(path=path):
                self.assertIn('ARCH_PATH_NOT_ALLOWED:' + path, self.errors('synthetic', path))

    def test_git_guard_requires_feature_artifacts_and_exact_branch(self):
        # Commit inert synthetic blobs. Execute only the unchanged trusted guard.
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)

            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args],
                                               stderr=subprocess.PIPE).decode().strip()

            def commit():
                git('add', '.')
                git('commit', '-m', 'synthetic fixture')
                return git('rev-parse', 'HEAD')

            git('init', '-b', 'main')
            git('config', 'user.name', 'Synthetic Test')
            git('config', 'user.email', 'synthetic@example.invalid')
            target = repo / MANIFEST
            target.parent.mkdir(parents=True)
            target.write_text(json.dumps(self.policy))
            base = commit()
            for path in self.policy['required_artifacts']:
                target = repo / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('synthetic fixture\n')
            (repo / MODULE / 'src/main/assets/presence/andy.glb').write_bytes(b'glTF\0\xffCameraX')
            complete = commit()
            (repo / SOURCE / 'PresenceStage.kt').unlink()
            missing = commit()
            git('switch', '--detach', base)
            for head, branch, expected in (
                (complete, self.policy['branch'], 'ARCH_PASS'),
                (missing, self.policy['branch'], 'ARCH_REQUIRED_ARTIFACT_MISSING'),
                (complete, self.policy['branch'] + '-extra', 'ARCH_FRONTIER_UNKNOWN'),
            ):
                result = subprocess.run([
                    sys.executable, '-I', str(ROOT / 'scripts/architecture/check_guardrails.py'),
                    '--base-ref', base, '--head-ref', head, '--branch', branch,
                ], cwd=repo, capture_output=True, text=True, timeout=10)
                self.assertIn(expected, result.stdout, result.stderr)
                self.assertEqual(result.returncode, int(expected != 'ARCH_PASS'))
            self.assertEqual(git('rev-parse', 'HEAD'), base)
