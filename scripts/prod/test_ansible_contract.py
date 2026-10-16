"""Behavioral deployment checks without SSH, containers or registry traffic."""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import subprocess
import tarfile
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy/ansible/files"))
from deploy_release import Deployment
from release_contract import DeploymentError, FileLock, safe_extract
from install_recovery_snapshot import MAX_INPUT_BYTES, install
from recovery_support import source_identity
import test_recovery_coordinator as recovery_tests
from test_recovery_coordinator import source_fixture


class Commands:
    def __init__(self, fixture):
        self.fixture = fixture
        self.calls = []
        self.fail = None
        self.existing = False
        self.healthy = True
        self.recovery_started = False
        self.stale_recovery = False
        self.fail_install = False
        self.expected_source = None
        self.initial_unit_state = "inactive"
        self.actual_images = None
        self.writer_states = {"server": "running", "backup": "running"}

    def inspect(self, container):
        images = self.actual_images or {name: self.fixture.compose()["services"][name]["image"] for name in ("server", "backup", "postgres")}
        containers, _ = source_fixture("fixture", self.fixture.objects, images)
        name = container.removesuffix("-id")
        value = containers[name]
        value["State"]["Status"] = self.writer_states.get(name, "running")
        return json.dumps([value])

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        if self.fail and self.fail(argv):
            raise DeploymentError("COMMAND_FAILED")
        if argv[:2] == ["docker", "info"]:
            return "fixture-daemon"
        if argv[:2] == ["docker", "ps"]:
            return next(value.split("=", 2)[-1] + "-id" for value in argv if value.startswith("label=com.docker.compose.service="))
        if argv[:2] == ["docker", "inspect"]:
            return self.inspect(argv[-1])
        if argv[:2] == ["uname", "-s"]:
            return "Linux"
        if "--version" in argv or "version" in argv or "$PSVersionTable.PSVersion.ToString()" in argv:
            return "2.24.4" if argv[:2] == ["docker", "compose"] else "1.2.3"
        if "verify-published-release.ps1" in " ".join(argv):
            return ""
        if "config" in argv:
            return json.dumps(self.fixture.compose())
        if "ps" in argv:
            if not self.existing:
                return "[]"
            return json.dumps([{"Service": name, "ID": name + "-original", "State": self.writer_states.get(name, "running"),
                                "Image": (self.actual_images or {}).get(name, self.fixture.compose()["services"][name]["image"]),
                                "Health": "healthy" if self.healthy else "unhealthy"}
                               for name in self.fixture.components])
        if argv[:3] == ["docker", "volume", "ls"]:
            return "fixture_postgres-data" if self.existing else ""
        if argv[0] == "ssh":
            if argv[-1].endswith("install_recovery_snapshot.py"):
                payload = json.loads(kwargs["input"])
                if payload.get("action") == "validate-future":
                    return json.dumps({"status": "RECOVERY_FUTURE_CONFIG_VERIFIED"})
                if payload.get("action") == "preflight":
                    self.expected_source = payload["expectedSource"]
                    return json.dumps({"status": "RECOVERY_SOURCE_VERIFIED", "sourceIdentity": self.expected_source})
                if self.fail_install:
                    raise DeploymentError("COMMAND_FAILED")
                return json.dumps({"status": "RECOVERY_SNAPSHOT_INSTALLED"})
            if "date" in argv:
                return str(int(time.time()))
            if "show" in argv:
                stamp = 200 if self.recovery_started and not self.stale_recovery else 100
                state = "inactive" if self.recovery_started else self.initial_unit_state
                return f"Result=success\nActiveState={state}\nExecMainStatus=0\nExecMainStartTimestampMonotonic={stamp}\nExecMainExitTimestampMonotonic={stamp + 1}"
            if "start" in argv:
                self.recovery_started = True
            if "cat" in argv:
                return json.dumps({"schemaVersion": 1, "status": "PASS", "setId": "fixture-set", "lastSuccessPoint": time.time(),
                                   "sourceProject": "fixture", "sourceIdentity": self.expected_source})
        if argv[0] == "bash":
            self.existing = True
            self.actual_images = {name: self.fixture.compose()["services"][name]["image"] for name in self.fixture.components}
            self.writer_states.update(server="running", backup="running")
        if "stop" in argv:
            for name in argv[argv.index("stop") + 1:]:
                self.writer_states[name] = "exited"
        return ""


class CoordinatorBridge:
    def __init__(self, app):
        self.app = app
        self.fixture = recovery_tests.RecoveryTest()
        self.fixture.setUp()
        app.addCleanup(self.fixture.doCleanups)
        config = self.fixture.config
        config["project"] = "fixture"
        config["objects"] = {**app.objects, "accessKeyFile": config["recovery"]["accessKeyFile"],
                             "secretKeyFile": config["recovery"]["secretKeyFile"]}
        self.path = self.fixture.root / "recovery.json"
        self.path.write_text(json.dumps(config))
        self.path.chmod(0o600)
        Path(config["composeFiles"][0]).write_text(json.dumps(app.compose()))
        runtime = self.fixture.root / "recovery-runtime.env"
        runtime.write_text("DOCKER_HOST=ssh://fixture-app\n")
        runtime.chmod(0o600)
        Path(config["outputDirectory"]).mkdir()
        self.status = {}

    def refresh(self):
        self.fixture.config = json.loads(self.path.read_text())
        self.fixture.rendered = json.loads(Path(self.fixture.config["composeFiles"][0]).read_text())
        images = self.app.commands.actual_images or {name: self.app.compose()["services"][name]["image"] for name in self.app.components}
        self.fixture.source_containers, _ = source_fixture("fixture", self.app.objects, images)
        self.fixture.writer_states = self.app.commands.writer_states

    def __call__(self, args, **kwargs):
        raw = self.app.commands(args, **kwargs)
        self.refresh()
        if args[0] == "ssh" and args[-1].endswith("install_recovery_snapshot.py"):
            def ops(command, **options):
                return "inactive" if command[0] == "systemctl" else self.fixture.command(command, **options)
            return json.dumps(install(kwargs["input"].encode(), self.path, Path(self.fixture.config["outputDirectory"]), ops))
        if args[0] == "ssh" and "systemctl" in args and "start" in args:
            self.status = recovery_tests.Coordinator(self.fixture.config, run=self.fixture.command).execute()
        if args[0] == "ssh" and "cat" in args:
            return json.dumps(self.status)
        return raw


class DeploymentTests(unittest.TestCase):
    components = ("server", "web", "backup", "postgres", "typesense", "clamav")

    def setUp(self):
        local = ROOT / ".local"
        local.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="ansible-contract-", dir=local)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.root = self.directory / "target"
        self.incoming = self.root / "incoming/session"
        self.assets = self.incoming / "assets"
        self.assets.mkdir(parents=True)
        self.environment = self.incoming / "environment"
        self.environment.write_text("PROJECT_NAME=fixture\nDB_PASSWORD=synthetic-input\n")
        self.environment.chmod(0o600)
        (self.incoming / "verify-published-release.ps1").write_text("trusted controller verifier")
        (self.incoming / "secret").write_text("synthetic-file-input")
        (self.incoming / "secret").chmod(0o600)
        self.recovery = self.directory / "recovery.json"
        self.recovery.write_text(json.dumps({"project": "fixture", "outputDirectory": str(self.directory / "recovery-output")}))
        self.recovery.chmod(0o600)
        self.spec = {"root": str(self.root), "incoming": str(self.incoming), "repository": "owner/repo",
            "version": "v1.2.3", "expected_source_sha": "a" * 40, "project": "fixture", "environment": str(self.environment),
            "assets": str(self.assets), "verifier": str(self.incoming / "verify-published-release.ps1"),
            "secrets": [{"variable": "DB_PASSWORD_FILE", "name": "database-password", "source": str(self.incoming / "secret")}],
            "overlays": [], "recovery_host": "smartupcms-ops", "health_timeout": 180,
            "prerequisites": {name: "1.2.3" for name in ("docker", "compose", "powershell", "cosign", "gh")}}
        self.spec["prerequisites"]["compose"] = "2.24.4"
        self.make_assets()
        self.commands = Commands(self)
        self.ownership = []
        self.objects = {"provider": "s3", "endpoint": "https://objects.example.invalid", "bucket": "private-app", "region": "auto"}

    def make_assets(self):
        version = self.spec["version"]
        with tarfile.open(self.assets / f"smartupcms-{version}.tar.gz", "w:gz") as archive:
            for name in ("docker-compose.yml", "scripts/prod/deploy.sh", "scripts/prod/recovery_coordinator.py"):
                item = tarfile.TarInfo(f"smartupcms-{version}/{name}")
                body = b"fixture bundle content"
                item.size = len(body)
                archive.addfile(item, io.BytesIO(body))
        for index, name in enumerate(self.components):
            (self.assets / f"smartupcms-{name}-{version}.image.txt").write_text(
                f"ghcr.io/owner/smartupcms/{name}@sha256:{str(index + 1) * 64}")
        (self.assets / "SHA256SUMS").write_text("trusted fixture manifest " + version)
        self.spec["checksums_sha256"] = hashlib.sha256((self.assets / "SHA256SUMS").read_bytes()).hexdigest()

    def compose(self):
        services = {name: {"image": (self.assets / f"smartupcms-{name}-{self.spec['version']}.image.txt").read_text()}
                    for name in self.components}
        services["migrate"] = {"image": services["server"]["image"], "profiles": ["tools"], "read_only": True}
        services["backup-bootstrap"] = {"image": services["backup"]["image"], "profiles": ["tools"], "read_only": True}
        _, source = source_fixture("fixture", self.objects, {name: services[name]["image"] for name in ("server", "backup", "postgres")})
        for name in source["services"]:
            services[name].update(source["services"][name])
        return {"name": "fixture", "services": services, "volumes": {"postgres-data": {"name": "fixture_postgres-data"}},
                "networks": source["networks"]}

    def deploy(self, **kwargs):
        return Deployment(self.spec, run=self.commands, owner=lambda path, uid: self.ownership.append((path, uid))).execute(**kwargs)

    def staging_candidate(self, version="v1.2.3-rc.2"):
        self.spec.update(version=version, release_channel="staging", staging=True)
        overlays = self.incoming / "overlays"
        overlays.mkdir(exist_ok=True)
        self.spec["overlays"] = []
        for name in ("observability.yml", "staging.yml"):
            path = overlays / name
            path.write_text("services: {}")
            self.spec["overlays"].append(str(path))
        self.make_assets()

    def test_default_channel_passes_stable_and_expected_source_to_verifier(self):
        self.deploy()
        args = next(args for args, _ in self.commands.calls if "verify-published-release.ps1" in " ".join(args))
        self.assertIn("-Channel", args)
        self.assertEqual(args[args.index("-Channel") + 1], "stable")
        self.assertIn("-ExpectedSourceSha", args)
        self.assertEqual(args[args.index("-ExpectedSourceSha") + 1], "a" * 40)

    def test_invalid_or_missing_expected_source_stops_before_external_commands(self):
        for value in (None, "", "a" * 39, "A" * 40, "g" * 40):
            with self.subTest(value=value):
                if value is None:
                    self.spec.pop("expected_source_sha", None)
                else:
                    self.spec["expected_source_sha"] = value
                with self.assertRaisesRegex(DeploymentError, "INVALID_CONFIGURATION"):
                    self.deploy()
                self.assertEqual(len(self.commands.calls), 0)

    def test_release_channel_and_version_must_agree(self):
        for channel, version in (("stable", "v1.2.3-rc.1"), ("staging", "v1.2.3"),
                                 ("preview", "v1.2.3"), ("staging", "v1.2.3-rc.0"),
                                 ("staging", "v1.2.3-rc.01"), ("staging", "v1.2.3-beta.1")):
            with self.subTest(channel=channel, version=version):
                self.spec.update(release_channel=channel, version=version)
                with self.assertRaisesRegex(DeploymentError, "INVALID_CONFIGURATION"):
                    self.deploy()
                self.assertEqual(len(self.commands.calls), 0)

    def test_prerelease_requires_explicit_staging_selector_and_both_overlays(self):
        self.staging_candidate()
        for value in (None, False, "true"):
            with self.subTest(staging=value):
                self.spec["staging"] = value
                with self.assertRaisesRegex(DeploymentError, "STAGING_TARGET_REQUIRED"):
                    self.deploy()
                self.assertEqual(len(self.commands.calls), 0)
        self.spec["staging"] = True
        self.spec["overlays"].pop()
        with self.assertRaisesRegex(DeploymentError, "STAGING_TARGET_REQUIRED"):
            self.deploy()
        self.assertEqual(len(self.commands.calls), 0)

    def test_explicit_staging_prerelease_deploys_and_repeat_is_unchanged(self):
        self.staging_candidate()
        self.assertTrue(self.deploy()["changed"])
        args = next(args for args, _ in self.commands.calls if "verify-published-release.ps1" in " ".join(args))
        self.assertEqual(args[args.index("-Channel") + 1], "staging")
        self.assertEqual(args[args.index("-ExpectedSourceSha") + 1], "a" * 40)
        self.assertFalse(self.deploy()["changed"])

    def test_prerelease_numeric_forward_upgrade_and_stable_promotion(self):
        self.staging_candidate()
        self.deploy()
        self.spec["version"] = "v1.2.3-rc.10"
        self.make_assets()
        self.assertTrue(self.deploy()["changed"])
        self.spec.update(version="v1.2.3", release_channel="stable")
        self.commands.recovery_started = False
        self.make_assets()
        self.assertTrue(self.deploy()["changed"])
        self.commands.calls.clear()
        self.spec.update(version="v1.2.3-rc.11", release_channel="staging")
        self.make_assets()
        with self.assertRaisesRegex(DeploymentError, "DOWNGRADE_REQUIRES_ROLLBACK_PLAN"):
            self.deploy()
        self.assertEqual(len(self.commands.calls), 0)

    def test_failed_prerelease_migration_rejects_previous_rc_without_commands(self):
        self.staging_candidate()
        self.deploy()
        self.spec["version"] = "v1.2.3-rc.10"
        self.make_assets()
        self.commands.fail = lambda args: args[0] == "bash"
        with self.assertRaisesRegex(DeploymentError, "COMMAND_FAILED"):
            self.deploy()
        self.spec["version"] = "v1.2.3-rc.2"
        self.make_assets()
        self.commands.fail = None
        self.commands.calls.clear()
        with self.assertRaisesRegex(DeploymentError, "FAILED_MIGRATION_REQUIRES_FORWARD_RELEASE"):
            self.deploy()
        self.assertEqual(len(self.commands.calls), 0)

    def test_private_secret_snapshots_use_the_actual_container_reader_uid(self):
        self.spec["secrets"].append({"variable": "MONITOR_DB_PASSWORD_FILE", "name": "monitor-password",
                                     "source": str(self.incoming / "secret")})
        self.deploy()
        owners = {path.name: uid for path, uid in self.ownership}
        self.assertEqual(owners, {"database-password": 10001, "monitor-password": 65534})

    def test_check_mode_does_not_write_files_or_call_commands(self):
        before = sorted(str(file) for file in self.directory.rglob("*"))
        self.assertEqual(self.deploy(check=True)["status"], "CHECK_ONLY")
        self.assertEqual(before, sorted(str(file) for file in self.directory.rglob("*")))
        self.assertEqual(self.commands.calls, [])

    def test_manifest_trust_anchor_mismatch_stops_before_external_verification(self):
        self.spec["checksums_sha256"] = "0" * 64
        with self.assertRaisesRegex(DeploymentError, "MANIFEST_TRUST_MISMATCH"):
            self.deploy()
        self.assertFalse(any("verify-published" in " ".join(args) for args, _ in self.commands.calls))

    def test_verifier_failure_prevents_docker_mutations(self):
        self.commands.fail = lambda argv: "verify-published-release.ps1" in " ".join(argv)
        with self.assertRaises(DeploymentError):
            self.deploy()
        self.assertFalse(any("pull" in args or "stop" in args or args[0] == "bash" for args, _ in self.commands.calls))

    def test_initial_deploy_verifies_then_runs_existing_deployment_with_private_composite_config(self):
        result = self.deploy()
        self.assertTrue(result["changed"])
        argv, options = next((args, options) for args, options in self.commands.calls if args[0] == "bash")
        compose = Path(options["env"]["COMPOSE_FILE"])
        self.assertTrue(compose.is_file())
        self.assertFalse(compose.is_relative_to(Path(options["cwd"])))
        self.assertEqual(json.loads(compose.read_text())["name"], "fixture")
        self.assertTrue(any("verify-published-release.ps1" in " ".join(args) for args, _ in self.commands.calls))

    def test_same_release_and_private_configuration_do_not_redeploy(self):
        self.deploy()
        self.commands.calls.clear()
        self.assertFalse(self.deploy()["changed"])
        self.assertFalse(any(args[0] == "bash" or "stop" in args or "pull" in args for args, _ in self.commands.calls))

    def test_secret_rotation_changes_private_identity_and_requires_complete_recovery(self):
        self.deploy()
        self.commands.calls.clear()
        (self.incoming / "secret").write_text("rotated synthetic input")
        self.assertTrue(self.deploy()["changed"])
        calls = [args for args, _ in self.commands.calls]
        recovery = next(i for i, args in enumerate(calls) if "smartupcms-recovery.service" in args and "start" in args)
        stop = next(i for i, args in enumerate(calls) if "stop" in args)
        deployment = next(i for i, args in enumerate(calls) if args[0] == "bash")
        self.assertLess(recovery, stop)
        self.assertLess(stop, deployment)

    def test_existing_stack_without_complete_recovery_host_is_refused(self):
        self.commands.existing = True
        self.spec["recovery_host"] = ""
        with self.assertRaisesRegex(DeploymentError, "RECOVERY_HOST_REQUIRED"):
            self.deploy()
        self.assertFalse(any("stop" in args or args[0] == "bash" for args, _ in self.commands.calls))

    def test_recovery_failure_prevents_quiesce_and_migration(self):
        self.commands.existing = True
        self.commands.fail = lambda args: "smartupcms-recovery.service" in args and "start" in args
        with self.assertRaises(DeploymentError):
            self.deploy()
        self.assertFalse(any("stop" in args or args[0] == "bash" for args, _ in self.commands.calls))

    def test_recovery_start_cannot_reuse_a_previous_successful_systemd_invocation(self):
        self.commands.existing = True
        self.commands.stale_recovery = True
        with self.assertRaisesRegex(DeploymentError, "FRESH_COMPLETE_RECOVERY_REQUIRED"):
            self.deploy()
        self.assertFalse(any("stop" in args or args[0] == "bash" for args, _ in self.commands.calls))

    def test_failed_terminal_unit_can_start_a_fresh_verified_capture(self):
        self.commands.existing = True
        self.commands.initial_unit_state = "failed"
        self.assertEqual(self.deploy()["status"], "DEPLOYED")
        self.assertTrue(self.commands.recovery_started)

    def test_partial_quiesce_failure_restarts_original_containers_only(self):
        self.commands.existing = True
        self.commands.fail = lambda args: "stop" in args and args[-1] == "backup"
        with self.assertRaises(DeploymentError):
            self.deploy()
        self.assertTrue(any(args == ["docker", "start", "server-original"] for args, _ in self.commands.calls))
        self.assertFalse(any(args[0] == "bash" for args, _ in self.commands.calls))

    def test_deployment_failure_keeps_writers_stopped_without_old_schema_restart(self):
        self.commands.existing = True
        self.commands.fail = lambda args: args[0] == "bash"
        with self.assertRaises(DeploymentError):
            self.deploy()
        self.assertFalse(any(args[:2] == ["docker", "start"] for args, _ in self.commands.calls))
        state = json.loads((self.root / "state.json").read_text())
        self.assertEqual(state["lastAttempt"]["status"], "FAILED")
        self.assertIsNone(state.get("current"))

    def test_changed_image_repository_or_tag_is_refused_before_start(self):
        original = self.compose
        def invalid():
            value = original()
            value["services"]["server"]["image"] = "untrusted.invalid/server:latest"
            return value
        self.compose = invalid
        with self.assertRaisesRegex(DeploymentError, "COMPOSE_IMAGE_MISMATCH"):
            self.deploy()
        self.assertFalse(any(args[0] == "bash" for args, _ in self.commands.calls))

    def test_parallel_deployment_lock_prevents_commands(self):
        with FileLock(self.root / "deploy.lock"):
            with self.assertRaisesRegex(DeploymentError, "DEPLOYMENT_LOCKED"):
                self.deploy()
        self.assertEqual(self.commands.calls, [])

    def test_arbitrary_rollback_execution_is_explicitly_unsupported(self):
        self.spec["action"] = "rollback"
        with self.assertRaisesRegex(DeploymentError, "ROLLBACK_EXECUTION_UNSUPPORTED"):
            self.deploy()
        self.assertEqual(self.commands.calls, [])

    def test_staging_outside_private_root_is_refused(self):
        self.spec["incoming"] = str(self.directory)
        with self.assertRaisesRegex(DeploymentError, "UNSAFE_PATH"):
            self.deploy()
        self.assertEqual(self.commands.calls, [])

    def test_forward_deploy_refuses_downgrade_without_executing_any_commands(self):
        self.deploy()
        self.commands.calls.clear()
        self.spec["version"] = "v1.2.2"
        self.make_assets()
        with self.assertRaisesRegex(DeploymentError, "DOWNGRADE_REQUIRES_ROLLBACK_PLAN"):
            self.deploy()
        self.assertEqual(self.commands.calls, [])

    def test_failed_migration_cannot_be_followed_by_previous_successful_version(self):
        self.deploy()
        self.spec["version"] = "v1.2.4"
        self.make_assets()
        self.commands.fail = lambda args: args[0] == "bash"
        with self.assertRaises(DeploymentError):
            self.deploy()
        self.spec["version"] = "v1.2.3"
        self.make_assets()
        self.commands.fail = None
        self.commands.calls.clear()
        with self.assertRaisesRegex(DeploymentError, "FAILED_MIGRATION_REQUIRES_FORWARD_RELEASE"):
            self.deploy()
        self.assertEqual(self.commands.calls, [])

    def test_published_version_cannot_be_replaced_with_another_manifest(self):
        self.deploy()
        self.commands.calls.clear()
        (self.assets / "SHA256SUMS").write_text("replacement manifest")
        self.spec["checksums_sha256"] = hashlib.sha256((self.assets / "SHA256SUMS").read_bytes()).hexdigest()
        with self.assertRaisesRegex(DeploymentError, "IMMUTABLE_VERSION_CHANGED"):
            self.deploy()
        self.assertEqual(self.commands.calls, [])

    def test_snapshot_sync_failure_retries_only_sync_and_keeps_new_writers_running(self):
        self.commands.existing = True
        self.commands.fail_install = True
        with self.assertRaises(DeploymentError):
            self.deploy()
        state = json.loads((self.root / "state.json").read_text())
        self.assertEqual(state["lastAttempt"]["phase"], "POSTDEPLOY_SYNC")
        self.assertIn("pendingRecoverySync", state)
        deployment = next(index for index, (args, _) in enumerate(self.commands.calls) if args[0] == "bash")
        self.assertFalse(any("stop" in args or args[:2] == ["docker", "start"] for args, _ in self.commands.calls[deployment + 1:]))
        self.commands.calls.clear()
        self.commands.fail_install = False
        self.assertEqual(self.deploy()["status"], "DEPLOYED")
        self.assertFalse(any(args[0] == "bash" or "stop" in args or "pull" in args for args, _ in self.commands.calls))
        self.assertTrue(any(args[-1].endswith("install_recovery_snapshot.py") for args, _ in self.commands.calls))

    def test_interrupted_migration_requires_review_before_any_commands(self):
        self.deploy()
        path = self.root / "state.json"
        state = json.loads(path.read_text())
        state["lastAttempt"].update(status="RUNNING", phase="DEPLOYMENT")
        path.write_text(json.dumps(state))
        self.commands.calls.clear()
        with self.assertRaisesRegex(DeploymentError, "INTERRUPTED_DEPLOYMENT_REQUIRES_REVIEW"):
            self.deploy()
        self.assertEqual(self.commands.calls, [])

    def test_pending_snapshot_cannot_be_replaced_with_another_private_configuration(self):
        self.commands.fail_install = True
        with self.assertRaises(DeploymentError):
            self.deploy()
        self.commands.fail_install = False
        self.commands.calls.clear()
        (self.incoming / "secret").write_text("different synthetic input")
        with self.assertRaisesRegex(DeploymentError, "PENDING_RECOVERY_SYNC_REQUIRED"):
            self.deploy()
        self.assertFalse(any(args[0] == "bash" or "stop" in args for args, _ in self.commands.calls))

    def test_fresh_pass_for_another_source_is_refused_before_migrations(self):
        self.commands.existing = True
        def wrong_status(args, **kwargs):
            raw = self.commands(args, **kwargs)
            if args[0] == "ssh" and "cat" in args:
                value = json.loads(raw)
                value["sourceProject"] = "another-project"
                return json.dumps(value)
            return raw
        with self.assertRaisesRegex(DeploymentError, "RECOVERY_SOURCE_MISMATCH"):
            Deployment(self.spec, run=wrong_status, owner=lambda *args: None).execute()
        self.assertFalse(any(args[0] == "bash" or "stop" in args for args, _ in self.commands.calls))

    def test_failed_migration_retry_uses_real_coordinator_and_keeps_old_writers_stopped(self):
        self.deploy()
        fixture = recovery_tests.RecoveryTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.config["project"] = "fixture"
        fixture.config["objects"] = {**self.objects, "accessKeyFile": fixture.config["recovery"]["accessKeyFile"],
                                      "secretKeyFile": fixture.config["recovery"]["secretKeyFile"]}
        result = {}
        def integration(args, **kwargs):
            raw = self.commands(args, **kwargs)
            if args[0] == "ssh" and "systemctl" in args and "start" in args:
                fixture.config["expectedSource"] = self.commands.expected_source
                fixture.writer_states = self.commands.writer_states
                fixture.source_containers, fixture.rendered = source_fixture("fixture", self.objects,
                    {name: self.compose()["services"][name]["image"] for name in ("server", "backup", "postgres")})
                result.update(recovery_tests.Coordinator(fixture.config, run=fixture.command).execute())
            if args[0] == "ssh" and "cat" in args:
                return json.dumps(result)
            return raw
        self.spec["version"] = "v1.2.4"
        self.make_assets()
        self.commands.fail = lambda args: args[0] == "bash"
        with self.assertRaises(DeploymentError):
            Deployment(self.spec, run=integration, owner=lambda *args: None).execute()
        self.assertEqual(self.commands.writer_states, {"server": "exited", "backup": "exited"})
        fixture.calls.clear()
        self.commands.fail = None
        self.commands.recovery_started = False
        self.assertEqual(Deployment(self.spec, run=integration, owner=lambda *args: None).execute()["status"], "DEPLOYED")
        self.assertEqual(result["status"], "PASS")
        self.assertTrue(any(args[0] == "aws" for args in fixture.calls))
        self.assertFalse(any("start" in args or "up" in args or "stop" in args for args in fixture.calls))

    def test_readiness_failure_forward_retry_binds_recreated_backup_before_real_capture(self):
        self.deploy()
        bridge = CoordinatorBridge(self)
        self.spec["version"] = "v1.2.4"
        self.make_assets()
        backup = self.assets / "smartupcms-backup-v1.2.4.image.txt"
        backup.write_text("ghcr.io/owner/smartupcms/backup@sha256:" + "b" * 64)
        self.commands.healthy = False
        with self.assertRaisesRegex(DeploymentError, "DEPLOYMENT_NOT_HEALTHY"):
            Deployment(self.spec, run=bridge, owner=lambda *args: None).execute()
        self.assertEqual(self.commands.writer_states, {"server": "exited", "backup": "exited"})
        self.spec["version"] = "v1.2.5"
        self.make_assets()
        self.commands.healthy = True
        self.commands.recovery_started = False
        bridge.fixture.calls.clear()
        self.assertEqual(Deployment(self.spec, run=bridge, owner=lambda *args: None).execute()["status"], "DEPLOYED")
        self.assertEqual(bridge.status["sourceIdentity"]["services"]["backup"]["image"], backup.read_text())
        self.assertTrue(any(args[0] == "aws" for args in bridge.fixture.calls))
        self.assertFalse(any("start" in args or "up" in args or "stop" in args for args in bridge.fixture.calls))

    def test_rendered_compose_preserves_literal_dollars_when_compose_reads_it_again(self):
        value = self.compose()
        value["services"]["server"]["environment"] = {"LITERAL": "fixture$$UNDEFINED_CREDENTIAL_TEXT"}
        value["services"]["postgres"]["volumes"] = ["postgres-data:/var/lib/postgresql/data"]
        source = self.incoming / "literal-compose.json"
        source.write_text(json.dumps(value))
        initial = subprocess.run(["docker", "compose", "-f", str(source), "--profile", "tools", "config", "--format", "json"],
                                 text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        rendered = json.loads(initial.stdout)
        self.compose = lambda: rendered
        self.deploy()
        state = json.loads((self.root / "state.json").read_text())
        result = subprocess.run(["docker", "compose", "-f", state["current"]["compose"], "config", "--format", "json"],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertEqual(result.returncode, 0)
        self.assertTrue(json.loads(result.stdout)["services"]["server"]["environment"]["LITERAL"]
                        == rendered["services"]["server"]["environment"]["LITERAL"])

    def test_rollback_plan_requires_tested_immutable_previous_release_and_never_runs_docker(self):
        self.deploy()
        previous = self.spec["checksums_sha256"]
        self.spec["version"] = "v1.2.4"
        self.make_assets()
        self.deploy()
        self.commands.calls.clear()
        self.spec["action"] = "rollback-plan"
        approval = self.incoming / "approval.json"
        approval.write_text(json.dumps({"targetVersion": "v1.2.3", "targetChecksumsSha256": previous,
            "currentChecksumsSha256": self.spec["checksums_sha256"], "applicationRollbackTested": True,
            "evidenceReference": "operator-reviewed-schema-test"}))
        approval.chmod(0o600)
        self.spec["rollback_approval"] = str(approval)
        self.assertEqual(self.deploy()["status"], "ROLLBACK_PLAN_ONLY")
        self.assertEqual(self.commands.calls, [])
        self.assertFalse(json.loads((self.root / "rollback-plan.json").read_text())["executeSupported"])

    def test_rollback_plan_rejects_an_untested_previous_release(self):
        self.deploy()
        self.spec["action"] = "rollback-plan"
        approval = self.incoming / "approval.json"
        approval.write_text(json.dumps({"targetVersion": "v1.2.3", "applicationRollbackTested": False}))
        approval.chmod(0o600)
        self.spec["rollback_approval"] = str(approval)
        with self.assertRaisesRegex(DeploymentError, "TESTED_PREVIOUS_RELEASE_REQUIRED"):
            self.deploy()


class ArchiveTests(unittest.TestCase):
    def test_safe_extract_rejects_traversal_absolute_links_and_devices(self):
        for name, kind in [("../escape", tarfile.REGTYPE), ("/escape", tarfile.REGTYPE),
                           ("smartupcms-v1.2.3/../../escape", tarfile.REGTYPE),
                           ("smartupcms-v1.2.3/link", tarfile.SYMTYPE),
                           ("smartupcms-v1.2.3/hard", tarfile.LNKTYPE),
                           ("smartupcms-v1.2.3/device", tarfile.CHRTYPE)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                archive_path = Path(temp) / "bundle.tar.gz"
                with tarfile.open(archive_path, "w:gz") as archive:
                    item = tarfile.TarInfo(name)
                    item.type = kind
                    item.linkname = "/outside"
                    archive.addfile(item)
                with self.assertRaisesRegex(DeploymentError, "UNSAFE_ARCHIVE"):
                    safe_extract(archive_path, Path(temp) / "expanded", "v1.2.3")


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ansible-snapshot-", dir=ROOT / ".local")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.recovery = self.root / "recovery"
        self.recovery.mkdir()
        self.config = self.root / "recovery.json"
        self.original = {"schemaVersion": 1, "project": "fixture", "outputDirectory": str(self.recovery),
                         "composeFiles": ["old.yml"], "envFile": "old.env",
                         "objects": {"provider": "s3", "endpoint": "https://objects.example.invalid", "bucket": "private-app", "region": "auto"},
                         "recovery": {"bucket": "private-recovery"}, "maxDurationSeconds": 3600, "ageRecipient": "age1fixture"}
        self.config.write_text(json.dumps(self.original))
        self.config.chmod(0o600)
        runtime = self.root / "recovery-runtime.env"
        runtime.write_text("DOCKER_HOST=ssh://fixture-app\n")
        runtime.chmod(0o600)
        services = {name: {"image": "registry.invalid/" + name + "@sha256:" + "1" * 64, "read_only": True}
                    for name in DeploymentTests.components}
        services["migrate"] = dict(services["server"])
        services["backup-bootstrap"] = dict(services["backup"])
        self.containers, rendered = source_fixture("fixture", self.original["objects"], {name: services[name]["image"] for name in ("server", "backup", "postgres")})
        for name in rendered["services"]:
            services[name].update(rendered["services"][name])
        self.payload = {"schemaVersion": 1, "project": "fixture", "compose": {"name": "fixture", "services": services, "networks": rendered["networks"]}}
        self.payload["expectedSource"] = source_identity("fixture", self.command)

    def command(self, args, **kwargs):
        if args[:2] == ["docker", "info"]:
            return "fixture-daemon"
        if args[:2] == ["docker", "ps"]:
            return next(value.split("=", 2)[-1] + "-id" for value in args if value.startswith("label=com.docker.compose.service="))
        if args[:2] == ["docker", "inspect"]:
            return json.dumps([self.containers[args[-1].removesuffix("-id")]])
        if "config" in args:
            return json.dumps(self.payload["compose"])
        return "inactive"

    def perform(self, run=None):
        return install(json.dumps(self.payload).encode(), self.config, self.recovery, run or self.command)

    def test_snapshot_atomically_preserves_private_storage_configuration_and_limits(self):
        self.assertEqual(self.perform()["status"], "RECOVERY_SNAPSHOT_INSTALLED")
        result = json.loads(self.config.read_text())
        for key in ("objects", "recovery", "maxDurationSeconds", "project"):
            self.assertEqual(result[key], self.original[key])
        self.assertEqual(json.loads(Path(result["composeFiles"][0]).read_text()), self.payload["compose"])
        self.assertEqual(Path(result["envFile"]).read_bytes(), b"")
        self.assertFalse((self.recovery / ".recovery.lock").exists())

    def test_snapshot_cannot_change_project_or_remove_immutable_readonly_contract(self):
        for field, value in (("image", "registry.invalid/server:latest"), ("read_only", False)):
            original = self.payload["compose"]["services"]["server"][field]
            self.payload["compose"]["services"]["server"][field] = value
            with self.assertRaisesRegex(DeploymentError, "RECOVERY_COMPOSE_INVALID"):
                self.perform()
            self.payload["compose"]["services"]["server"][field] = original
        self.payload["project"] = "another"
        with self.assertRaisesRegex(DeploymentError, "RECOVERY_PROJECT_MISMATCH"):
            self.perform()
        self.assertEqual(json.loads(self.config.read_text()), self.original)

    def test_snapshot_shares_coordinator_lock_and_refuses_active_unit(self):
        lock = self.recovery / ".recovery.lock"
        lock.write_text("")
        with self.assertRaisesRegex(DeploymentError, "RECOVERY_ALREADY_RUNNING"):
            self.perform()
        lock.unlink()
        with self.assertRaisesRegex(DeploymentError, "RECOVERY_ALREADY_RUNNING"):
            self.perform(run=lambda args, **kwargs: "activating")
        self.assertEqual(json.loads(self.config.read_text()), self.original)
        self.assertFalse(lock.exists())

    def test_snapshot_rejects_oversized_input_before_any_mutation(self):
        with self.assertRaisesRegex(DeploymentError, "RECOVERY_SNAPSHOT_TOO_LARGE"):
            install(b" " * (MAX_INPUT_BYTES + 1), self.config, self.recovery)
        self.assertEqual(json.loads(self.config.read_text()), self.original)

    def test_snapshot_helper_allows_a_failed_terminal_recovery_unit(self):
        def run(args, **kwargs):
            return "failed" if args[0] == "systemctl" else self.command(args, **kwargs)
        self.assertEqual(self.perform(run)["status"], "RECOVERY_SNAPSHOT_INSTALLED")

    def test_future_config_rejects_storage_and_recipient_changes_before_install(self):
        self.payload.update(action="validate-future", daemonId="fixture-daemon")
        for service, key, value in (("server", "SMC_S3_BUCKET", "different-bucket"), ("backup", "AGE_RECIPIENT", "age1different")):
            original = self.payload["compose"]["services"][service]["environment"][key]
            self.payload["compose"]["services"][service]["environment"][key] = value
            with self.assertRaisesRegex(RuntimeError, "storage or encryption"):
                self.perform()
            self.payload["compose"]["services"][service]["environment"][key] = original
        self.assertEqual(json.loads(self.config.read_text()), self.original)

    def test_preflight_rejects_other_daemon_even_with_same_project_and_configuration(self):
        self.payload["action"] = "preflight"
        self.payload["expectedSource"]["daemonId"] = "another-daemon"
        with self.assertRaisesRegex(RuntimeError, "source identity"):
            self.perform()
        self.assertEqual(json.loads(self.config.read_text()), self.original)

    def test_remote_local_object_path_cannot_be_mistaken_for_app_storage(self):
        config = json.loads(self.config.read_text())
        config["objects"] = {"provider": "local_disk", "localStoragePath": "/looks/the/same"}
        self.config.write_text(json.dumps(config))
        with self.assertRaisesRegex(DeploymentError, "REMOTE_LOCAL_OBJECT_CAPTURE_UNSUPPORTED"):
            self.perform()


if __name__ == "__main__":
    unittest.main()
