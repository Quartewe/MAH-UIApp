from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import build_mah
import yaml


class ReleaseTests(unittest.TestCase):
    def test_uiapp_tag_only_publishes_an_announcement(self):
        path = Path(__file__).resolve().parents[1] / ".github/workflows/mah-android.yml"
        workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
        trigger = workflow.get("on", workflow.get(True))
        self.assertEqual(trigger, {"push": {"tags": ["v*"]}})
        steps = [step for job in workflow["jobs"].values() for step in job["steps"]]
        self.assertEqual(len(steps), 1)
        self.assertTrue(steps[0]["uses"].startswith("softprops/action-gh-release@"))
        self.assertNotIn("files", steps[0]["with"])
        self.assertTrue(steps[0]["with"]["generate_release_notes"])

    def test_main_repository_version_argument_still_builds_apk_and_project(self):
        calls = []
        args = ["build_mah.py", "--mah", str(Path(__file__).parent), "--resources", str(Path(__file__).parent),
                "--version", "v2.0.1", "--skip-runtime"]
        result = subprocess.CompletedProcess([], 0, stdout="v1.0.0-alpha1\n")
        with patch("sys.argv", args), patch.object(build_mah.subprocess, "run", return_value=result), \
                patch.object(build_mah, "run", side_effect=lambda *a, **kw: calls.append((a, kw))):
            build_mah.main()
        stage, gradle = calls
        self.assertEqual(stage[0][-1], "v2.0.1")
        self.assertIn("-Pbuild.versionName=v2.0.1", gradle[0])
        self.assertIn("-Pbuild.projectVersion=v2.0.1", gradle[0])
        self.assertIn("-Pbuild.uiappVersion=v1.0.0-alpha1", gradle[0])
        self.assertIn(":app:assembleDebug", gradle[0])
        self.assertEqual(gradle[1]["env"]["BUILD_VERSION_NAME"], "v2.0.1")

    def test_explicit_uiapp_tag_does_not_change_apk_version(self):
        calls = []
        args = ["build_mah.py", "--version", "v2.0.1", "--uiapp-version", "v3.0.0", "--skip-runtime", "--release"]
        with patch("sys.argv", args), patch.object(build_mah, "run", side_effect=lambda *a, **kw: calls.append((a, kw))):
            build_mah.main()
        self.assertIn("-Pbuild.versionName=v2.0.1", calls[-1][0])
        self.assertIn("-Pbuild.uiappVersion=v3.0.0", calls[-1][0])
        self.assertIn(":app:assembleRelease", calls[-1][0])


if __name__ == "__main__":
    unittest.main()
