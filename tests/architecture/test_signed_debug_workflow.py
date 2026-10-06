import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github/workflows/signed-debug-apk.yml"
FRONTIER_PATH = ROOT / ".github/architecture/frontiers/android-build-versioning-v1.json"


class SignedDebugWorkflowPolicyTests(unittest.TestCase):
    def workflow(self) -> str:
        return WORKFLOW_PATH.read_text()

    def sections(self) -> tuple[str, str]:
        text = self.workflow()
        build = text.split("  build-candidate:\n", 1)[1].split("  sign-candidate:\n", 1)[0]
        sign = text.split("  sign-candidate:\n", 1)[1].split("  publish-firebase:\n", 1)[0]
        return build, sign

    def test_build_versioning_frontier_is_exact(self):
        manifest = json.loads(FRONTIER_PATH.read_text())
        self.assertEqual(manifest["frontier_id"], "android-build-versioning-v1")
        self.assertEqual(manifest["branch"], "feat/android-build-versioning-v1")
        self.assertEqual(manifest["allowed_paths"], ["app/build.gradle.kts"])
        self.assertEqual(manifest["required_artifacts"], ["app/build.gradle.kts"])

    def test_candidate_build_is_owner_dispatched_and_secret_free(self):
        build, _ = self.sections()
        self.assertIn("github.actor == github.repository_owner", build)
        self.assertIn("persist-credentials: false", build)
        self.assertIn("test \"$(git rev-parse HEAD)\" = \"$CANDIDATE_SHA\"", build)
        self.assertIn("-PandyVersionCode=", build)
        self.assertIn("-PandyVersionName=", build)
        self.assertNotIn("secrets.", build)
        self.assertNotIn("ANDY_SIGNING_", build)

    def test_candidate_build_requires_and_injects_public_configuration(self):
        build, _ = self.sections()
        self.assertIn("ATTENTION_ROUTER_BASE_URL: ${{ vars.ATTENTION_ROUTER_BASE_URL }}", build)
        self.assertIn("GOOGLE_WEB_CLIENT_ID: ${{ vars.GOOGLE_WEB_CLIENT_ID }}", build)
        self.assertIn('test -n "${ATTENTION_ROUTER_BASE_URL//[[:space:]]/}"', build)
        self.assertIn('test -n "${GOOGLE_WEB_CLIENT_ID//[[:space:]]/}"', build)
        self.assertLess(build.index('test -n "${GOOGLE_WEB_CLIENT_ID'), build.index("./gradlew"))
        self.assertIn('-PattentionRouterBaseUrl="$ATTENTION_ROUTER_BASE_URL"', build)
        self.assertIn('-PgoogleWebClientId="$GOOGLE_WEB_CLIENT_ID"', build)

    def test_signing_job_never_executes_candidate_code(self):
        _, sign = self.sections()
        self.assertIn("needs: build-candidate", sign)
        self.assertIn("github.actor == github.repository_owner", sign)
        self.assertNotIn("actions/checkout", sign)
        self.assertNotIn("./gradlew", sign)
        self.assertNotIn("git ", sign)

    def test_signing_job_uses_only_stable_secret_boundary(self):
        _, sign = self.sections()
        for secret in (
            "secrets.ANDY_SIGNING_KEYSTORE_B64",
            "secrets.ANDY_SIGNING_ALIAS",
            "secrets.ANDY_SIGNING_STORE_PASSWORD",
            "secrets.ANDY_SIGNING_KEY_PASSWORD",
        ):
            self.assertIn(secret, sign)
        self.assertIn(
            "dab9de67da84d79560d817b4b6f2bd4abbf27641cc596b30f0510d331cdf5e3f",
            sign,
        )
        self.assertIn("apksigner", sign)
        self.assertIn("zipalign", sign)
        self.assertIn("name: andy-debug-apk-signed", sign)


if __name__ == "__main__":
    unittest.main()
