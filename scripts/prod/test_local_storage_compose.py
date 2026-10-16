"""The production local-disk recovery profile binds the exact application object root."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from recovery_support import object_source, verify_capture_settings


class LocalStorageComposeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name).as_posix()
        self.repo = Path(__file__).resolve().parents[2]

    def render(self, configured=True):
        env = os.environ.copy()
        env.pop("SMC_LOCAL_STORAGE_HOST_PATH", None)
        if configured:
            env["SMC_LOCAL_STORAGE_HOST_PATH"] = self.source
        return subprocess.run([
            "docker", "compose", "--project-directory", str(self.repo),
            "-f", str(self.repo / "deploy/compose/docker-compose.prod.yml"),
            "-f", str(self.repo / "deploy/compose/docker-compose.local-storage.yml"),
            "--env-file", str(self.repo / "scripts/prod/release-config.test.env"),
            "--profile", "tools", "config", "--format", "json"], env=env,
            capture_output=True, text=True, timeout=30, check=False)

    def test_rendered_profile_matches_the_recovery_source(self):
        result = self.render()
        self.assertEqual(result.returncode, 0, "Local-storage Compose profile must render")
        rendered = json.loads(result.stdout)
        server = rendered["services"]["server"]
        target = server["environment"]["SMC_STORAGE_LOCAL_PATH"]
        mounts = [v for v in server["volumes"] if v["target"] == target]
        self.assertEqual(len(mounts), 1)
        self.assertEqual(mounts[0]["type"], "bind")
        self.assertEqual(Path(mounts[0]["source"]), Path(self.source))
        self.assertFalse(mounts[0]["bind"]["create_host_path"])
        objects = {"provider": "local_disk", "localStoragePath": mounts[0]["source"]}
        config = {"objects": objects, "ageRecipient": rendered["services"]["backup"]["environment"]["AGE_RECIPIENT"]}
        verify_capture_settings(config, rendered)
        inspected = {"Config": {"Env": [key + "=" + value for key, value in server["environment"].items()]},
                     "Mounts": [{"Type": "bind", "Source": mounts[0]["source"], "Destination": target}]}
        self.assertEqual(object_source(inspected), objects)
        config["objects"]["localStoragePath"] += "-wrong"
        with self.assertRaisesRegex(RuntimeError, "storage or encryption"):
            verify_capture_settings(config, rendered)

    def test_missing_host_source_is_rejected_before_deployment(self):
        self.assertNotEqual(self.render(configured=False).returncode, 0)


if __name__ == "__main__":
    unittest.main()
