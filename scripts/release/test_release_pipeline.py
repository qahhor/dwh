"""Execute release gates and bundle construction without network or publishing."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts/release"
COMPONENTS = ("server", "web", "backup", "postgres", "typesense", "clamav")


def load(name):
    path = SCRIPTS / (name + ".py")
    if not path.is_file():
        raise AssertionError("Required release implementation is missing: " + name)
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class Fixture(unittest.TestCase):
    def setUp(self):
        local = ROOT / ".local"
        local.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="release-pipeline-test-", dir=local)
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Release fixture")
        self.git("config", "user.email", "release-fixture@example.invalid")
        (self.root / "DCO").write_text("Synthetic DCO fixture\n")
        self.commit()
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True, stderr=subprocess.PIPE).strip()

    def commit(self, signed=True):
        self.git("add", ".")
        self.git("commit", "--allow-empty", "-m", "Release fixture", *(["-s"] if signed else []))
        return self.git("rev-parse", "HEAD")

    def env(self, version, sha=None):
        source = sha or self.git("rev-parse", "HEAD")
        return {**os.environ, "GITHUB_EVENT_NAME": "push", "GITHUB_REF_TYPE": "tag",
                "GITHUB_REF_NAME": version, "GITHUB_REF": "refs/tags/" + version,
                "GITHUB_SHA": source, "GITHUB_RUN_ATTEMPT": "1",
                "STAGING_APPROVED_SOURCE_SHA": source, "STAGING_APPROVED_VERSION": version,
                "DEFAULT_BRANCH": "main", "GITHUB_REPOSITORY": "example/cms"}


class RefTests(Fixture):
    def gate(self, channel, env):
        return subprocess.run([sys.executable, str(SCRIPTS / "release_policy.py"), "--channel", channel],
                              cwd=self.root, env=env, text=True, capture_output=True)

    def test_staging_allows_signed_off_main_commit_stable_requires_ancestry(self):
        sha = self.commit()
        self.git("tag", "v1.2.3-rc.1")
        self.git("tag", "v1.2.3")
        self.assertEqual(self.gate("staging", self.env("v1.2.3-rc.1")).returncode, 0)
        denied = self.gate("stable", self.env("v1.2.3"))
        self.assertNotEqual(denied.returncode, 0)
        self.git("update-ref", "refs/remotes/origin/main", sha)
        self.assertEqual(self.gate("stable", self.env("v1.2.3")).returncode, 0)

    def test_ref_sha_event_and_rerun_are_bound(self):
        self.commit()
        version = "v1.2.3-rc.1"
        self.git("tag", version)
        valid = self.env(version)
        for key, value in (("GITHUB_SHA", "a" * 40), ("GITHUB_REF_TYPE", "branch"),
                           ("GITHUB_REF", "refs/heads/main"), ("GITHUB_EVENT_NAME", "workflow_dispatch"),
                           ("GITHUB_RUN_ATTEMPT", "2"), ("GITHUB_RUN_ATTEMPT", "")):
            with self.subTest(key=key):
                result = self.gate("staging", {**valid, key: value})
                self.assertNotEqual(result.returncode, 0)
        self.commit()
        self.assertNotEqual(self.gate("staging", self.env(version)).returncode, 0)

    def test_channel_and_version_validation(self):
        policy = load("release_policy")
        for valid in ("v0.1.0-rc.1", "v12.34.56-rc.123"):
            self.assertEqual(policy.release_version("staging", valid), valid)
        for invalid in ("v1.2.3", "v01.2.3-rc.1", "v1.2.3-rc.0", "v1.2.3-rc.01",
                        "v1.2.3-rc.1+build", "v1.2.3-rc.1\n", "v1.2.3-beta.1"):
            with self.assertRaises(ValueError): policy.release_version("staging", invalid)
        with self.assertRaises(ValueError): policy.release_version("stable", "v1.2.3-rc.1")

    def test_unsigned_post_dco_range_is_rejected(self):
        self.commit(signed=False)
        self.commit()
        version = "v1.2.3-rc.2"
        self.git("tag", version)
        result = self.gate("staging", self.env(version))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("DCO", result.stderr)

    def test_staging_writer_needs_exact_environment_approval(self):
        policy = load("release_policy")
        version = "v1.2.3-rc.1"
        self.git("tag", version)
        valid = self.env(version)
        for key, value in (("STAGING_APPROVED_SOURCE_SHA", ""), ("STAGING_APPROVED_VERSION", ""),
                           ("STAGING_APPROVED_SOURCE_SHA", "b" * 40), ("STAGING_APPROVED_VERSION", "v1.2.3-rc.2")):
            with self.subTest(key=key, value=value):
                with self.assertRaises(ValueError):
                    policy.check_context(self.root, "staging", {**valid, key: value}, write_only=True)


class BundleTests(Fixture):
    def prepare(self, channel="staging", version="v1.2.3-rc.1"):
        self.policy = load("release_policy")
        self.builder = load("build_release_bundle")
        self.output = self.root / "dist"
        self.output.mkdir()
        for file in self.builder.FILES:
            path = self.root / file
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic " + file + "\n")
        for folder in self.builder.TREES:
            directory = self.root / folder
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "fixture.txt").write_text("tracked\n")
        for path in self.builder.ENVIRONMENTS:
            file = self.root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            prefix = "ACCEPTANCE_EXPECTED_" if "acceptance" in path or "managed" in path else ""
            file.write_text("IMAGE_REGISTRY=placeholder\nAPP_VERSION=placeholder\n" +
                            "".join(f"{prefix}{name.upper()}_IMAGE=placeholder\n" for name in COMPONENTS))
        self.sha = self.commit()
        for component in COMPONENTS:
            stem = self.output / f"smartupcms-{component}-{version}"
            for suffix, value in (("image.txt", f"ghcr.io/example/smartupcms/{component}@sha256:" + "a" * 64),
                                  ("spdx.json", '{"spdxVersion":"SPDX-2.3"}'),
                                  ("cdx.json", '{"bomFormat":"CycloneDX"}'),
                                  ("provenance.json", '{"mediaType":"synthetic"}')):
                Path(str(stem) + "." + suffix).write_text(value + "\n")
        self.options = dict(channel=channel, version=version, source_sha=self.sha, repository="example/cms", output=self.output)

    def test_both_archives_manifest_and_all_templates_bind_source_and_images(self):
        self.prepare()
        secret = self.root / "deploy/staging/private.env"
        secret.write_text("never-copy-local-secret")
        self.builder.build(self.root, **self.options)
        manifest = json.loads((self.output / "release.json").read_text())
        self.assertEqual((manifest["channel"], manifest["sourceSha"], len(manifest["images"])), ("staging", self.sha, 6))
        with tarfile.open(self.output / "smartupcms-v1.2.3-rc.1.tar.gz") as tar:
            archive = {entry.name: tar.extractfile(entry).read() for entry in tar.getmembers() if entry.isfile()}
            self.assertEqual(tar.getmember("smartupcms-v1.2.3-rc.1/scripts/prod/deploy.sh").mode, 0o755)
        with zipfile.ZipFile(self.output / "smartupcms-v1.2.3-rc.1.zip") as zipped:
            self.assertEqual(archive, {name: zipped.read(name) for name in zipped.namelist()})
            self.assertEqual((zipped.getinfo("smartupcms-v1.2.3-rc.1/scripts/prod/deploy.sh").external_attr >> 16) & 0o777, 0o755)
        prefix = "smartupcms-v1.2.3-rc.1/"
        self.assertEqual(archive[prefix + "REVISION"].decode().strip(), self.sha)
        self.assertEqual(json.loads(archive[prefix + "release.json"]), manifest)
        self.assertFalse(any(name.endswith("private.env") for name in archive))
        for path in (".env.example", *self.builder.ENVIRONMENTS):
            value = archive[prefix + path].decode()
            self.assertNotIn("placeholder", value)
            for image in manifest["images"]: self.assertIn(image, value)
        lines = (self.output / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(len(lines), 27)
        for line in lines:
            digest, name = line.split("  ")
            self.assertEqual(hashlib.sha256((self.output / name).read_bytes()).hexdigest(), digest)
        with self.assertRaises(ValueError): self.builder.build(self.root, **self.options)

    def test_missing_metadata_wrong_image_sha_and_dirty_source_fail_closed(self):
        self.prepare()
        path = self.output / "smartupcms-server-v1.2.3-rc.1.image.txt"
        original = path.read_text()
        for value in ("ghcr.io/other/smartupcms/server@sha256:" + "a" * 64, "ghcr.io/example/smartupcms/server:latest"):
            path.write_text(value)
            with self.assertRaises(ValueError): self.builder.build(self.root, **self.options)
            self.assertFalse((self.output / "SHA256SUMS").exists())
        path.write_text(original)
        with self.assertRaises(ValueError): self.builder.build(self.root, **{**self.options, "source_sha": "b" * 40})
        source = self.root / "DCO"
        original_source = source.read_text()
        source.write_text("changed after source SHA was accepted")
        with self.assertRaises(ValueError): self.builder.build(self.root, **self.options)
        source.write_text(original_source)
        path.unlink()
        with self.assertRaises(ValueError): self.builder.build(self.root, **self.options)

    def test_stable_uses_identical_bundle_structure_with_stable_manifest(self):
        self.prepare(channel="stable", version="v1.2.3")
        self.builder.build(self.root, **self.options)
        self.assertEqual(json.loads((self.output / "release.json").read_text())["channel"], "stable")
        self.assertTrue((self.output / "smartupcms-v1.2.3.tar.gz").is_file())
        self.assertTrue((self.output / "smartupcms-v1.2.3.zip").is_file())


class PublishTests(Fixture):
    def test_staging_is_prerelease_never_latest_and_rerun_makes_no_call(self):
        load("release_policy")
        publish = load("publish_release")
        version = "v1.2.3-rc.1"
        self.git("tag", version)
        output = self.root / "dist"
        output.mkdir()
        for name in publish.assets(version): (output / name).write_text("synthetic artifact")
        calls = []
        def run(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 0)
        with patch.dict(os.environ, self.env(version), clear=True):
            publish.publish(self.root, "staging", version, output, run=run)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][:4], ["gh", "release", "create", version])
        self.assertIn("--prerelease", calls[0])
        self.assertIn("--latest=false", calls[0])
        calls.clear()
        with patch.dict(os.environ, {**self.env(version), "GITHUB_RUN_ATTEMPT": "2"}, clear=True):
            with self.assertRaises(ValueError): publish.publish(self.root, "staging", version, output, run=run)
        self.assertFalse(calls)

    def test_stable_flags_and_missing_assets_do_not_bypass_publication_policy(self):
        load("release_policy")
        publish = load("publish_release")
        version = "v1.2.3"
        self.git("tag", version)
        output = self.root / "dist"
        output.mkdir()
        for name in publish.assets(version): (output / name).write_text("synthetic artifact")
        calls = []
        def run(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 0)
        with patch.dict(os.environ, self.env(version), clear=True):
            publish.publish(self.root, "stable", version, output, run=run)
            self.assertNotIn("--prerelease", calls[0])
            self.assertNotIn("--latest=false", calls[0])
            calls.clear()
            (output / "SHA256SUMS.sigstore.json").unlink()
            with self.assertRaises(ValueError): publish.publish(self.root, "stable", version, output, run=run)
            self.assertFalse(calls)


if __name__ == "__main__":
    unittest.main()
