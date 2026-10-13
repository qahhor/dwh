"""Recovery invariants with disposable files and controlled command boundaries."""
import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from recovery_coordinator import Coordinator
from recovery_support import digest, prune_sets, source_identity, validate_config
from verify_recovery_set import verify


def source_fixture(project, objects, images=None):
    storage = {"SMC_PROVIDER_STORAGE": objects["provider"], "SMC_STORAGE_LOCAL_PATH": "/objects"}
    if objects["provider"] == "s3":
        storage.update(SMC_S3_ENDPOINT=objects["endpoint"], SMC_S3_BUCKET=objects["bucket"], SMC_S3_REGION=objects.get("region", "auto"))
    envs = {"server": {**storage, "DB_URL": "jdbc:postgresql://postgres:5432/smartupcms",
                        "WAREHOUSE_URL": "jdbc:postgresql://postgres:5432/smartupcms_dwh"},
            "backup": {"PGHOST": "postgres", "PGDATABASE": "smartupcms", "WAREHOUSE_DB_NAME": "smartupcms_dwh", "AGE_RECIPIENT": "age1fixture"},
            "postgres": {"POSTGRES_DB": "smartupcms", "WAREHOUSE_DB_NAME": "smartupcms_dwh"}}
    containers = {name: {"Config": {"Env": [key + "=" + value for key, value in values.items()],
                                    "Image": images[name] if images else "registry.invalid/" + name + "@sha256:" + "1" * 64,
                                    "Labels": {"com.docker.compose.project": project, "com.docker.compose.service": name,
                                               "com.docker.compose.config-hash": "config-" + name}}, "State": {"Status": "running"}, "Mounts": [],
                        "NetworkSettings": {"Networks": {project + "_backend": {"NetworkID": "fixture-network"}}}}
                  for name, values in envs.items()}
    containers["postgres"]["Mounts"] = [{"Type": "volume", "Name": project + "_postgres-data", "Source": "/docker/data", "Destination": "/var/lib/postgresql"}]
    if objects["provider"] == "local_disk":
        containers["server"]["Mounts"] = [{"Type": "bind", "Source": objects["localStoragePath"], "Destination": "/objects"}]
    rendered = {"name": project, "networks": {"backend": {"name": project + "_backend"}},
                "services": {name: {"image": item["Config"]["Image"], "environment": envs[name], "read_only": True, "networks": {"backend": None},
                "volumes": [{"type": mount["Type"], "source": mount["Source"], "target": mount["Destination"]} for mount in item["Mounts"]]}
                for name, item in containers.items()}}
    return containers, rendered


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        self.config = {
            "schemaVersion": 1, "project": "smartupcms-test",
            "composeFiles": [str(self.root / "compose.yml")],
            "envFile": str(self.root / "private.env"),
            "outputDirectory": str(self.root / "sets"),
            "metricsFile": str(self.root / "metrics" / "recovery.prom"),
            "ageRecipient": "age1fixture", "maxDurationSeconds": 3600,
            "minFreeBytes": 1, "minFreePercent": 1, "maxLocalSets": 2,
            "objects": {"provider": "local_disk", "localStoragePath": str(self.root / "objects")},
            "recovery": {"endpoint": "https://recovery.example.invalid", "bucket": "recovery",
                         "prefix": "sets", "accessKeyFile": str(self.root / "access"),
                         "secretKeyFile": str(self.root / "secret")},
        }
        for name in ("compose.yml", "private.env", "access", "secret"):
            (self.root / name).write_text("disposable-" + name)
            (self.root / name).chmod(0o600)
        (self.root / "objects").mkdir()
        self.calls = []
        self.remote = {}
        self.failure_phase = None
        self.stamp = None
        self.point = None
        self.writer_states = {"server": "running", "backup": "running"}
        self.source_containers, self.rendered = source_fixture(self.config["project"], self.config["objects"])
        self.config["expectedSource"] = source_identity(self.config["project"], self.command)
        self.calls.clear()

    def command(self, args, **kwargs):
        self.calls.append(args)
        if args[:2] == ["docker", "info"]:
            return "fixture-daemon"
        if args[:2] == ["docker", "ps"]:
            return next(value.split("=", 2)[-1] + "-id" for value in args if value.startswith("label=com.docker.compose.service="))
        if args[:2] == ["docker", "inspect"] and "--format" not in args:
            value = self.source_containers[args[-1].removesuffix("-id")]
            value["State"]["Status"] = self.writer_states.get(args[-1].removesuffix("-id"), "running")
            return json.dumps([value])
        if "config" in args:
            return json.dumps(self.rendered)
        if self.failure_phase == "database" and args[:2] == ["docker", "inspect"]:
            return "exited 1"
        if "ps" in args:
            if "--status" in args and self.writer_states.get(args[-1]) != "running":
                return ""
            return args[-1] + "-id"
        if args[-2:] == ["stop", "server"] or args[-2:] == ["stop", "backup"]:
            self.writer_states[args[-1]] = "exited"
        if args[-2:] == ["start", "server"] or args[-2:] == ["start", "backup"]:
            self.writer_states[args[-1]] = "running"
        if "inspect" in args:
            return "exited 0"
        if args[-1] == "cat /status/control-version":
            return "1"
        if args[-1].startswith("if [ -e /status/running"):
            return "idle"
        for arg in args:
            if arg.startswith("BACKUP_TIMESTAMP="):
                self.stamp = arg.split("=", 1)[1]
            if arg.startswith("BACKUP_CONSISTENT_POINT="):
                self.point = arg.split("=", 1)[1]
        if "cp" in args and args[0] == "docker":
            destination = Path(args[-1])
            destination.mkdir(exist_ok=True)
            for name in ("smartupcms", "smartupcms_dwh"):
                file = destination / (name + "-" + self.stamp + ".dump.age")
                file.write_bytes(b"encrypted-database")
                Path(str(file) + ".sha256").write_text(digest(file) + "  " + file.name)
                manifest = Path(str(file) + ".manifest.json")
                manifest.write_text(json.dumps({"schemaVersion": 1, "archiveSha256": digest(file), "capturedAt": self.point}))
                Path(str(manifest) + ".sha256").write_text(digest(manifest) + "  " + manifest.name)
        if args[0] == "pwsh":
            if self.failure_phase == "objects":
                raise RuntimeError("object capture failed")
            destination = Path(args[args.index("-OutputDirectory") + 1])
            file = destination / "smartupcms-objects-fixture.tar.age"
            file.write_bytes(b"encrypted-objects")
            Path(str(file) + ".sha256").write_text(digest(file) + "  " + file.name)
        if args[0] == "aws" and "cp" in args:
            source, target = args[args.index("cp") + 1:args.index("cp") + 3]
            if source.startswith("s3://"):
                Path(target).write_bytes(b"corrupt" if self.failure_phase == "remote" else self.remote[source])
            else:
                self.remote[target] = Path(source).read_bytes()
        return ""

    def run_coordinator(self):
        return Coordinator(self.config, run=self.command).execute()

    def test_success_quiesces_before_capture_and_publishes_manifest_last(self):
        result = self.run_coordinator()
        self.assertEqual("PASS", result["status"])
        verified = verify(Path(self.config["outputDirectory"]) / result["setId"])
        self.assertEqual(result["setId"], verified["setId"])
        stop = next(i for i, c in enumerate(self.calls) if c[-2:] == ["stop", "server"])
        objects = next(i for i, c in enumerate(self.calls) if c[0] == "pwsh")
        resume = next(i for i, c in enumerate(self.calls) if c[-2:] == ["start", "server"])
        self.assertLess(stop, objects)
        self.assertLess(objects, resume)
        uploads = [c for c in self.calls if c[0] == "aws" and "cp" in c and not c[c.index("cp")+1].startswith("s3://")]
        self.assertTrue(uploads[-1][uploads[-1].index("cp")+2].endswith("/complete.json"))
        self.assertTrue(Path(self.config["metricsFile"]).read_text().find("smc_recovery_last_attempt_success 1") >= 0)
        rules = (Path(__file__).resolve().parents[2] /
                 "deploy/observability/prometheus/rules/operations.yml").read_text()
        references = set(re.findall(r"\bsmc_recovery_[a-z_]+", rules))
        exported = {line.split()[0] for line in Path(self.config["metricsFile"]).read_text().splitlines()}
        self.assertTrue(references <= exported)

    def test_stopped_writers_are_captured_without_starting_unsafe_schema(self):
        self.writer_states = {"server": "exited", "backup": "exited"}
        self.assertEqual(self.run_coordinator()["status"], "PASS")
        self.assertFalse(any("start" in c or "up" in c or "stop" in c for c in self.calls))
        self.assertEqual(self.writer_states, {"server": "exited", "backup": "exited"})

    def test_capture_restores_only_the_initially_running_writer(self):
        self.writer_states["server"] = "exited"
        self.assertEqual(self.run_coordinator()["status"], "PASS")
        self.assertFalse(any(args[-2:] == ["start", "server"] for args in self.calls))
        self.assertTrue(any(args[-2:] == ["start", "backup"] for args in self.calls))
        self.assertEqual(self.writer_states, {"server": "exited", "backup": "running"})

    def test_failed_detached_capture_is_removed_before_any_writer_resumes(self):
        self.failure_phase = "database"
        with self.assertRaises(RuntimeError):
            self.run_coordinator()
        removed = next(i for i, c in enumerate(self.calls) if c[:3] == ["docker", "rm", "-f"])
        resumed = next(i for i, c in enumerate(self.calls) if c[-2:] == ["start", "server"])
        self.assertLess(removed, resumed)

    def test_source_identity_mismatch_prevents_any_writer_stop_or_capture(self):
        for key, value in (("daemonId", "wrong-daemon"), ("project", "wrong-project"),
                           ("postgresMounts", [{"Source": "wrong-volume"}])):
            with self.subTest(key=key):
                original = self.config["expectedSource"][key]
                self.config["expectedSource"][key] = value
                self.calls.clear()
                with self.assertRaisesRegex(RuntimeError, "source identity"):
                    self.run_coordinator()
                self.assertFalse(any("stop" in args or "BACKUP_RUN_ONCE=true" in args for args in self.calls))
                self.config["expectedSource"][key] = original

    def test_capture_configuration_cannot_redirect_objects_recipient_or_network(self):
        mutations = [(self.config, "ageRecipient", "age1wrong"),
                     (self.config["objects"], "localStoragePath", str(self.root)),
                     (self.rendered["networks"]["backend"], "name", "another_backend")]
        for target, key, value in mutations:
            original = target[key]
            target[key] = value
            self.calls.clear()
            with self.assertRaises((RuntimeError, ValueError)):
                self.run_coordinator()
            self.assertFalse(any("stop" in args for args in self.calls))
            target[key] = original

    def test_failures_resume_only_started_services_and_never_publish_complete(self):
        for phase in ("database", "objects", "remote"):
            with self.subTest(phase=phase):
                self.failure_phase = phase
                self.calls.clear()
                self.remote.clear()
                with self.assertRaises(RuntimeError):
                    self.run_coordinator()
                self.assertTrue(any(c[-2:] == ["start", "server"] for c in self.calls))
                self.assertFalse(any(key.endswith("/complete.json") for key in self.remote))
                self.assertIn("smc_recovery_last_attempt_success 0", Path(self.config["metricsFile"]).read_text())

    def test_absent_running_server_fails_before_stop(self):
        def runner(args, **kwargs):
            self.calls.append(args)
            return ""
        with self.assertRaises(RuntimeError):
            Coordinator(self.config, run=runner).execute()
        self.assertFalse(any("stop" in c for c in self.calls))
        self.assertFalse(any("start" in c for c in self.calls))

    def test_no_space_fails_before_docker(self):
        with patch("recovery_coordinator.shutil.disk_usage", return_value=(100, 100, 0)):
            with self.assertRaisesRegex(RuntimeError, "space"):
                self.run_coordinator()
        self.assertEqual([], self.calls)

    def test_lock_refuses_overlapping_capture(self):
        root = Path(self.config["outputDirectory"])
        root.mkdir()
        (root / ".recovery.lock").write_text("other-run")
        with self.assertRaisesRegex(RuntimeError, "lock"):
            self.run_coordinator()
        self.assertEqual([], self.calls)

    def test_config_rejects_insecure_remote_and_relative_paths(self):
        for key, value in (("endpoint", "http://example.invalid"), ("prefix", "../escape")):
            with self.subTest(key=key):
                config = json.loads(json.dumps(self.config))
                config["recovery"][key] = value
                with self.assertRaises(ValueError):
                    validate_config(config)
        self.config["outputDirectory"] = "."
        with self.assertRaises(ValueError):
            validate_config(self.config)

    def test_prune_preserves_foreign_directories(self):
        root = self.root / "sets"
        root.mkdir()
        foreign = root / "operator-data"
        foreign.mkdir()
        (foreign / "keep.txt").write_text("keep")
        prune_sets(root, 1)
        self.assertTrue((foreign / "keep.txt").exists())

    def test_corrupt_previous_status_does_not_leave_lock(self):
        root = Path(self.config["outputDirectory"])
        root.mkdir()
        (root / "status.json").write_text("{")
        with self.assertRaises(ValueError):
            self.run_coordinator()
        self.assertFalse((root / ".recovery.lock").exists())
        self.assertEqual([], self.calls)

    def test_failed_attempt_preserves_last_verified_point(self):
        root = Path(self.config["outputDirectory"])
        root.mkdir()
        (root / "status.json").write_text(json.dumps({"lastSuccessPoint": 1234}))
        self.failure_phase = "objects"
        with self.assertRaises(RuntimeError):
            self.run_coordinator()
        state = json.loads((root / "status.json").read_text())
        self.assertEqual(1234, state["lastSuccessPoint"])
        self.assertEqual("FAIL", state["status"])

    def test_source_tree_cannot_include_recovery_output(self):
        self.config["objects"]["localStoragePath"] = str(self.root)
        with self.assertRaises(ValueError):
            validate_config(self.config)

    def test_unversioned_backup_worker_is_not_stopped(self):
        def runner(args, **kwargs):
            return "" if args[-1] == "cat /status/control-version" else self.command(args, **kwargs)
        with self.assertRaisesRegex(RuntimeError, "control"):
            Coordinator(self.config, run=runner).execute()
        self.assertFalse(any("stop" in c for c in self.calls))

    def test_restore_verifier_refuses_corrupt_archive_and_path_traversal(self):
        result = self.run_coordinator()
        directory = Path(self.config["outputDirectory"]) / result["setId"]
        manifest = directory / "complete.json"
        value = json.loads(manifest.read_text())
        file = directory / value["files"][0]["name"]
        file.write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            verify(directory)
        value["files"][0]["name"] = "../escape"
        manifest.write_text(json.dumps(value))
        with self.assertRaises(ValueError):
            verify(directory)


if __name__ == "__main__":
    unittest.main()
