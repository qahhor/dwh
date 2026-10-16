"""Capture and remotely verify a consistent CMS/DWH/object set (NFR-DATA-04)."""
import argparse
import datetime as dt
import json
import os
import shutil
import signal
import time
import uuid
from pathlib import Path

from recovery_support import (atomic_json, command, digest, metrics, private_directory,
                              prune_sets, secret_environment, validate_config, verify_source)


class Coordinator:
    def __init__(self, config, run=command):
        self.config = validate_config(config)
        self.run = run
        self.started = time.monotonic()
        self.compose = ["docker", "compose", "-p", config["project"]]
        for file in config["composeFiles"]:
            self.compose += ["-f", file]
        self.compose += ["--env-file", config["envFile"]]
        self.server_stopped = self.backup_stopped = self.paused = False
        self.container = None
        self.pause_started = None
        self.pause_seconds = 0

    def call(self, args, env=None):
        remaining = self.config["maxDurationSeconds"] - (time.monotonic() - self.started)
        if remaining <= 0:
            raise RuntimeError("Recovery duration exceeded its approved bound")
        return self.run(args, timeout=max(1, min(remaining, 3600)), env=env)

    def comp(self, *args):
        return self.call(self.compose + list(args))

    def stop_writers(self):
        running = {}
        for service in ("server", "backup"):
            if len(self.comp("ps", "-a", "-q", service).split()) != 1:
                raise RuntimeError("Exactly one existing " + service + " service is required")
            running[service] = len(self.comp("ps", "--status", "running", "-q", service).split()) == 1
        probe = ("exec", "-T", "backup", "sh", "-c") if running["backup"] else (
            "run", "--rm", "--no-deps", "--entrypoint", "sh", "backup", "-c")
        if self.comp(*probe, "cat /status/control-version") != "1":
            raise RuntimeError("Backup control protocol is unsupported")
        if running["backup"]:
            self.paused = True
            self.comp("exec", "-T", "backup", "sh", "-c", "touch /status/pause")
            while True:
                state = self.comp("exec", "-T", "backup", "sh", "-c",
                                  "if [ -e /status/running ]; then printf busy; else printf idle; fi")
                if state == "idle":
                    break
                if state != "busy":
                    raise RuntimeError("Backup idle acknowledgement is invalid")
                time.sleep(1)
            self.backup_stopped = True
            self.comp("stop", "backup")
        if running["server"]:
            self.server_stopped = True
            self.pause_started = time.monotonic()
            self.comp("stop", "server")

    def resume(self):
        # Cleanup gets its own bounded deadline even when the capture timed out.
        failures = []
        if self.server_stopped:
            try:
                self.run(self.compose + ["start", "server"], timeout=120)
                self.run(self.compose + ["up", "-d", "--no-recreate", "--no-deps",
                                        "--wait", "--wait-timeout", "120", "server"], timeout=150)
                self.server_stopped = False
                self.pause_seconds = time.monotonic() - self.pause_started
            except RuntimeError:
                failures.append("server restart")
        if self.paused:
            try:
                self.run(self.compose + ["run", "--rm", "--no-deps", "--entrypoint", "sh", "backup",
                                        "-c", "rm -f /status/pause"], timeout=120)
                self.paused = False
            except RuntimeError:
                failures.append("backup pause cleanup")
        if self.backup_stopped and not self.paused:
            try:
                self.run(self.compose + ["start", "backup"], timeout=120)
                self.backup_stopped = False
            except RuntimeError:
                failures.append("backup restart")
        if failures:
            raise RuntimeError("Recovery cleanup failed: " + ", ".join(failures))

    def capture_databases(self, directory, stamp, point):
        self.container = self.config["project"] + "-capture-" + uuid.uuid4().hex[:12]
        self.comp("run", "-d", "--name", self.container, "--no-deps",
                  "-e", "BACKUP_RUN_ONCE=true", "-e", "BACKUP_STORAGE_MODE=local",
                  "-e", "BACKUP_DIR=/backups/" + directory.name,
                  "-e", "BACKUP_TIMESTAMP=" + stamp, "-e", "BACKUP_CONSISTENT_POINT=" + point,
                  "-e", "PGOPTIONS=-c statement_timeout=3300000", "backup")
        while True:
            state = self.call(["docker", "inspect", "--format",
                               "{{.State.Status}} {{.State.ExitCode}}", self.container])
            if state == "exited 0":
                break
            if state not in ("running 0", "created 0"):
                raise RuntimeError("Database capture did not complete successfully")
            time.sleep(1)
        self.call(["docker", "cp", self.container + ":/backups/" + directory.name + "/.", str(directory)])
        for prefix in ("smartupcms", "smartupcms_dwh"):
            archive = directory / (prefix + "-" + stamp + ".dump.age")
            for file in (archive, Path(str(archive) + ".manifest.json")):
                checksum = Path(str(file) + ".sha256")
                if not file.is_file() or not checksum.is_file() or checksum.read_text().split()[0] != digest(file):
                    raise RuntimeError("Database capture checksum is missing or invalid")

    def capture_objects(self, directory):
        settings = self.config["objects"]
        args = ["pwsh", "-NoProfile", "-File", str(Path(__file__).with_name("backup-objects.ps1")),
                "-Provider", settings["provider"], "-AgeRecipient", self.config["ageRecipient"],
                "-OutputDirectory", str(directory)]
        env = os.environ.copy()
        if settings["provider"] == "s3":
            args += ["-S3Endpoint", settings["endpoint"], "-S3Bucket", settings["bucket"],
                     "-S3Region", settings.get("region", "auto")]
            env = secret_environment(settings)
        else:
            args += ["-LocalStoragePath", settings["localStoragePath"]]
        scratch = private_directory(directory / "scratch")
        env.update(TMPDIR=str(scratch), TMP=str(scratch), TEMP=str(scratch))
        self.call(args, env=env)
        archives = list(directory.glob("smartupcms-objects-*.tar.age"))
        if len(archives) != 1:
            raise RuntimeError("Exactly one encrypted object archive is required")
        archive = archives[0]
        checksum = Path(str(archive) + ".sha256")
        if not checksum.is_file() or checksum.read_text().split()[0] != digest(archive):
            raise RuntimeError("Object archive checksum is invalid")

    def upload_verified(self, file, set_id, env):
        remote = self.config["recovery"]
        key = "/".join((remote["prefix"], set_id, file.name))
        uri = "s3://" + remote["bucket"] + "/" + key
        prefix = ["aws", "--endpoint-url", remote["endpoint"], "s3", "cp"]
        self.call(prefix + [str(file), uri, "--only-show-errors"], env=env)
        readback = file.with_name(".readback")
        try:
            self.call(prefix + [uri, str(readback), "--only-show-errors"], env=env)
            if digest(readback) != digest(file):
                raise RuntimeError("Remote encrypted backup checksum mismatch")
        finally:
            readback.unlink(missing_ok=True)

    def publish_capture(self, directory, source, point_text, set_id, env):
        files = [{"name": p.name, "sha256": digest(p), "bytes": p.stat().st_size}
                 for p in sorted(directory.iterdir()) if p.is_file()]
        for file in files:
            self.upload_verified(directory / file["name"], set_id, env)
        manifest = {"schemaVersion": 1, "setId": set_id, "consistentPoint": point_text,
                    "completedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                    "files": files, "sourceProject": self.config["project"], "sourceIdentity": source}
        pending = directory / "complete.json"
        atomic_json(pending, manifest)
        try:
            self.upload_verified(pending, set_id, env)
        except Exception:
            pending.unlink(missing_ok=True)
            raise

    def prepare_capture(self, root):
        # Evict only verified local cache copies; retained off-host sets are never removed here.
        prune_sets(root, self.config["maxLocalSets"] - 1)
        free = shutil.disk_usage(root)
        if free[2] < self.config["minFreeBytes"] or 100 * free[2] / free[0] < self.config["minFreePercent"]:
            raise RuntimeError("Recovery scratch has insufficient free space")
        self.call(["age", "--version"])
        env = secret_environment(self.config["recovery"])
        return env, verify_source(self.config, self.compose, self.call)

    def execute(self):
        root = private_directory(self.config["outputDirectory"])
        state_file = root / "status.json"
        previous = json.loads(state_file.read_text()) if state_file.exists() else {}
        lock = root / ".recovery.lock"
        try:
            fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            raise RuntimeError("Recovery lock exists; concurrent capture refused") from None
        os.close(fd)
        state = {"schemaVersion": 1, "status": "FAIL", "attemptTimestamp": time.time(),
                 "lastSuccessPoint": previous.get("lastSuccessPoint", 0)}
        directory = None
        try:
            env, source = self.prepare_capture(root)
            self.stop_writers()
            point = dt.datetime.now(dt.timezone.utc)
            stamp = point.strftime("%Y%m%dT%H%M%SZ")
            point_text = point.strftime("%Y-%m-%dT%H:%M:%SZ")
            set_id = "set-" + stamp + "-" + uuid.uuid4().hex[:12]
            directory = private_directory(root / set_id)
            self.capture_databases(directory, stamp, point_text)
            self.capture_objects(directory)
            # All source bytes are captured while writers are stopped. Upload does not extend the outage.
            self.resume()
            self.publish_capture(directory, source, point_text, set_id, env)
            state.update(status="PASS", lastSuccessPoint=point.timestamp(), setId=set_id,
                         sourceProject=self.config["project"], sourceIdentity=source)
            prune_sets(root, self.config["maxLocalSets"])
            return state
        except Exception:
            state["status"] = "FAIL"
            if directory and not (directory / "complete.json").exists():
                if directory.parent.resolve() != root.resolve() or directory.is_symlink():
                    raise RuntimeError("Unsafe incomplete capture path") from None
                shutil.rmtree(directory)
            raise
        finally:
            try:
                if self.container:
                    self.run(["docker", "rm", "-f", self.container], timeout=120)
                self.resume()
                if directory:
                    self.run(self.compose + ["run", "--rm", "--no-deps", "--entrypoint", "sh", "backup",
                                            "-c", "rm -rf /backups/" + directory.name], timeout=120)
            except RuntimeError:
                state["status"] = "FAIL"
                raise
            finally:
                state["durationSeconds"] = time.monotonic() - self.started
                state["pauseSeconds"] = self.pause_seconds
                try:
                    atomic_json(state_file, state)
                    metrics(self.config["metricsFile"], state)
                finally:
                    lock.unlink(missing_ok=True)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    def terminate(signum, frame):
        signal.signal(signum, signal.SIG_IGN)
        raise RuntimeError("Recovery interrupted; restoring writers")
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    try:
        config = validate_config(json.loads(Path(args.config).read_text(encoding="utf-8")))
        if not args.validate_only:
            Coordinator(config).execute()
        print("Recovery configuration validated." if args.validate_only else "Consistent recovery set verified.")
        return 0
    except (ValueError, OSError, RuntimeError):
        print("Recovery failed; inspect protected status and the operations runbook.", file=__import__("sys").stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
