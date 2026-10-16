"""Mutation checks execute the workflow supply-chain verifier locally."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class WorkflowContractTests(unittest.TestCase):
    def invoke(self, stable=None, staging=None, newline="\n"):
        local = ROOT / ".local"
        local.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="release-workflow-test-", dir=local) as directory:
            args = [shutil.which("pwsh") or "pwsh", "-NoProfile", "-File", str(ROOT / "scripts/release/verify-release.ps1")]
            for name, value, source in (
                ("WorkflowPath", stable, "release.yml"),
                ("PrereleaseWorkflowPath", staging, "prerelease.yml"),
            ):
                if value is None:
                    value = (ROOT / ".github/workflows" / source).read_text(encoding="utf-8")
                path = Path(directory) / (name + ".yml")
                path.write_bytes(value.replace("\n", newline).encode("utf-8"))
                args += ["-" + name, str(path)]
            return subprocess.run(args, cwd=ROOT, text=True, capture_output=True)

    def test_both_channel_contracts_accept_lf_and_crlf(self):
        stage = ROOT / ".github/workflows/prerelease.yml"
        self.assertTrue(stage.is_file(), "The isolated staging workflow must exist")
        for newline in ("\n", "\r\n"):
            with self.subTest(newline=repr(newline)):
                result = self.invoke(newline=newline)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_staging_boundary_mutations_fail(self):
        stage = ROOT / ".github/workflows/prerelease.yml"
        self.assertTrue(stage.is_file(), "The isolated staging workflow must exist")
        source = stage.read_text(encoding="utf-8")
        mutations = (
            source.replace("    environment: staging-release\n", "", 1),
            source.replace("    environment: staging-release\n", ""),
            source.replace("--channel staging", "--channel stable"),
            source.replace("    needs: [gate, ci]", "    needs: gate"),
            source.replace("    needs: build", "    needs: gate"),
            source.replace("      - 'v*.*.*-rc.*'", "      - 'v*'"),
            source.replace("  push:", "  workflow_dispatch:"),
            source.replace("--write-only", "--not-write-only"),
            source.replace("scan-type: image", "scan-type: none"),
            source.replace("sign-blob --yes", "version"),
            source.replace("subject-path: dist/SHA256SUMS", "subject-path: dist/release.json"),
            source.replace("permissions: {}", "permissions: write-all"),
            source.replace("    runs-on: ubuntu-24.04", "    continue-on-error: true\n    runs-on: ubuntu-24.04", 1),
        )
        for newline in ("\n", "\r\n"):
            for index, value in enumerate(mutations):
                with self.subTest(mutation=index, newline=repr(newline)):
                    self.assertNotEqual(value, source)
                    self.assertNotEqual(self.invoke(staging=value, newline=newline).returncode, 0)

    def test_stable_rejects_rc_trigger_and_missing_manifest_signature(self):
        source = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        mutations = (source.replace("      - '!v*.*.*-*'\n", ""),
                     source.replace("sign-blob --yes", "version"))
        for newline in ("\n", "\r\n"):
            for index, value in enumerate(mutations):
                with self.subTest(mutation=index, newline=repr(newline)):
                    self.assertNotEqual(value, source, "Stable workflow must have the new authenticated bundle boundary")
                    self.assertNotEqual(self.invoke(stable=value, newline=newline).returncode, 0)


if __name__ == "__main__":
    unittest.main()
