"""Target-side verified deployment; all subprocess output stays private."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time

from recovery_support import source_identity, source_location

from release_contract import (DeploymentError, FileLock, atomic_bytes, atomic_json, command,
                              contained, digest, own_secret, private_directory, private_file,
                              private_identity, safe_extract, tree_identity, version_order)

COMPONENTS = ("server", "web", "backup", "postgres", "typesense", "clamav")


class Deployment:
    def __init__(self, spec, run=command, owner=own_secret):
        self.spec, self.run = spec, run
        self.owner = owner
        self.root = Path(spec["root"])
        self.state = {}
        self.stopped = []
        self.deployment_started = False
        self.phase = "PREFLIGHT"
        self.process_env = {name: os.environ[name] for name in ("PATH", "HOME", "DOCKER_CONFIG", "GH_CONFIG_DIR") if name in os.environ}
        self.process_env["LANG"] = "C.UTF-8"
        self.process_env["DOCKER_HOST"] = "unix:///var/run/docker.sock"

    def validate(self):
        action = self.spec.get("action", "deploy")
        if action == "rollback":
            raise DeploymentError("ROLLBACK_EXECUTION_UNSUPPORTED")
        if action not in ("deploy", "rollback-plan"):
            raise DeploymentError("INVALID_ACTION")
        channel = self.spec.setdefault("release_channel", "stable")
        if channel not in ("stable", "staging"):
            raise DeploymentError("INVALID_CONFIGURATION")
        version_pattern = r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
        if channel == "staging":
            version_pattern += r"-rc\.[1-9][0-9]*"
        patterns = {"version": version_pattern, "expected_source_sha": r"[a-f0-9]{40}",
                    "repository": r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", "project": r"[a-z0-9][a-z0-9_-]*",
                    "checksums_sha256": r"[a-f0-9]{64}"}
        for key, pattern in patterns.items():
            if not isinstance(self.spec.get(key), str) or not re.fullmatch(pattern, self.spec[key]):
                raise DeploymentError("INVALID_CONFIGURATION")
        if type(self.spec.get("health_timeout", 180)) is not int or not 1 <= self.spec.get("health_timeout", 180) <= 3600:
            raise DeploymentError("INVALID_CONFIGURATION")
        if not self.root.is_absolute() or self.root.is_symlink():
            raise DeploymentError("UNSAFE_PATH")
        self.incoming = contained(self.root / "incoming", self.spec["incoming"])
        if channel == "staging" and (self.spec.get("staging") is not True or
                [Path(path) for path in self.spec.get("overlays", [])] !=
                [self.incoming / "overlays" / name for name in ("observability.yml", "staging.yml")]):
            raise DeploymentError("STAGING_TARGET_REQUIRED")
        self.assets = contained(self.incoming, self.spec["assets"])
        self.environment = contained(self.incoming, self.spec["environment"])
        self.verifier = contained(self.incoming, self.spec["verifier"])
        self.environment_bytes = private_file(self.environment)
        self.inputs = [("environment", self.environment_bytes), ("manifest", self.spec["checksums_sha256"].encode())]
        self.inputs += [("settings", json.dumps({key: self.spec.get(key) for key in
                        ("repository", "version", "release_channel", "expected_source_sha", "staging", "project", "prerequisites", "health_timeout")}, sort_keys=True).encode())]
        names = set()
        for item in self.spec["secrets"]:
            if (not re.fullmatch(r"[A-Z][A-Z0-9_]*_FILE", item["variable"])
                    or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", item["name"])
                    or item["variable"] in names or item["name"] in names):
                raise DeploymentError("INVALID_SECRET_MAPPING")
            names.update((item["variable"], item["name"]))
            self.inputs.append((item["variable"] + ":" + item["name"], private_file(contained(self.incoming, item["source"]))))
        for index, file in enumerate(self.spec.get("overlays", [])):
            self.inputs.append(("overlay:" + str(index), contained(self.incoming, file).read_bytes()))
        if digest(self.assets / "SHA256SUMS") != self.spec["checksums_sha256"]:
            raise DeploymentError("MANIFEST_TRUST_MISMATCH")
        tree_identity(self.assets)

    def call(self, argv, **kwargs):
        return self.run([str(value) for value in argv], env=self.process_env, **kwargs)

    def prerequisites(self):
        if self.call(["uname", "-s"]).strip() != "Linux":
            raise DeploymentError("LINUX_TARGET_REQUIRED")
        tools = {"docker": ["docker", "version", "--format", "{{.Server.Version}}"],
                 "compose": ["docker", "compose", "version", "--short"],
                 "powershell": ["pwsh", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"],
                 "cosign": ["cosign", "version"], "gh": ["gh", "--version"]}
        for name, argv in tools.items():
            expected = self.spec["prerequisites"].get(name, "")
            actual = re.search(r"\b(?:v)?(\d+\.\d+\.\d+)\b", self.call(argv))
            if not actual or actual[1] != expected:
                raise DeploymentError("PREREQUISITE_VERSION_MISMATCH")
        if tuple(map(int, self.spec["prerequisites"]["compose"].split("."))) < (2, 24, 4):
            raise DeploymentError("COMPOSE_VERSION_UNSUPPORTED")

    def verify_and_extract(self):
        self.phase = "VERIFICATION"
        self.call(["pwsh", "-NoProfile", "-File", self.verifier, "-ReleaseDirectory", self.assets,
                   "-Repository", self.spec["repository"], "-Version", self.spec["version"],
                   "-Channel", self.spec["release_channel"], "-ExpectedSourceSha", self.spec["expected_source_sha"],
                   "-EvidenceDirectory", self.incoming / "evidence"])
        candidate = self.incoming / "expanded"
        expanded = safe_extract(self.assets / ("smartupcms-" + self.spec["version"] + ".tar.gz"), candidate, self.spec["version"])
        self.release = self.root / "releases" / (self.spec["version"] + "-" + self.spec["checksums_sha256"])
        if self.release.exists():
            if tree_identity(self.release / "bundle") != tree_identity(expanded):
                raise DeploymentError("IMMUTABLE_RELEASE_CHANGED")
        else:
            private_directory(self.release)
            shutil.copytree(expanded, self.release / "bundle")
            shutil.copytree(self.assets, self.release / "assets")
        self.bundle = self.release / "bundle"
        self.images = {name: (self.assets / f"smartupcms-{name}-{self.spec['version']}.image.txt").read_text().strip() for name in COMPONENTS}

    def configuration(self):
        key_file = self.root / "identity.key"
        if not key_file.exists():
            atomic_bytes(key_file, os.urandom(32))
        self.identity = private_identity(private_file(key_file), self.inputs)
        self.config = self.root / "configurations" / self.identity
        private_directory(self.config)
        environment = self.environment_bytes + b"\n"
        for item in self.spec["secrets"]:
            target = self.config / "secrets" / item["name"]
            atomic_bytes(target, private_file(Path(item["source"])))
            self.owner(target, 65534 if item["variable"] == "MONITOR_DB_PASSWORD_FILE" else 10001)
            environment += f"{item['variable']}={target}\n".encode()
        for name, image in self.images.items():
            environment += f"{name.upper()}_IMAGE={image}\n".encode()
        environment += f"PROJECT_NAME={self.spec['project']}\nAPP_VERSION={self.spec['version']}\n".encode()
        self.env_file = self.config / "environment"
        atomic_bytes(self.env_file, environment)
        compose_args = ["docker", "compose", "-p", self.spec["project"], "--env-file", self.env_file, "-f", self.bundle / "docker-compose.yml"]
        for file in self.spec.get("overlays", []):
            compose_args += ["-f", file]
        rendered = json.loads(self.call(compose_args + ["--profile", "tools", "config", "--format", "json"]))
        if rendered.get("name") != self.spec["project"]:
            raise DeploymentError("COMPOSE_PROJECT_MISMATCH")
        expected = {**self.images, "migrate": self.images["server"], "backup-bootstrap": self.images["backup"]}
        for name, image in expected.items():
            if rendered.get("services", {}).get(name, {}).get("image") != image:
                raise DeploymentError("COMPOSE_IMAGE_MISMATCH")
        for service in rendered["services"].values():
            if "build" in service or not re.fullmatch(r"[^\s]+@sha256:[a-f0-9]{64}", service.get("image", "")):
                raise DeploymentError("COMPOSE_IMAGE_MISMATCH")
        self.rendered = rendered
        self.compose_file = self.config / "compose.json"
        atomic_json(self.compose_file, rendered)
        self.compose = ["docker", "compose", "-p", self.spec["project"], "-f", self.compose_file, "--env-file", self.env_file]

    def containers(self):
        raw = self.call(self.compose + ["ps", "-a", "--format", "json"])
        if not raw:
            return []
        return json.loads(raw) if raw.lstrip().startswith("[") else [json.loads(line) for line in raw.splitlines()]

    def healthy(self, containers):
        expected = {name for name, value in self.rendered["services"].items() if not value.get("profiles")}
        running = [item for item in containers if item.get("State") == "running"]
        ready = {item["Service"] for item in running if item.get("Health", "") in ("", "healthy")
                 and item.get("Image") == self.rendered["services"].get(item["Service"], {}).get("image")}
        return expected == ready and len(running) == len(expected)

    def recover(self):
        self.phase = "RECOVERY"
        expected = self.bind_recovery_source()
        alias = self.spec.get("recovery_host", "")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,62}", alias):
            raise DeploymentError("RECOVERY_HOST_REQUIRED")
        ssh = ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", alias]
        service = "smartupcms-recovery.service"
        properties = "Result,ActiveState,ExecMainStatus,ExecMainStartTimestampMonotonic,ExecMainExitTimestampMonotonic"
        query = ssh + ["sudo", "-n", "systemctl", "show", service, "--property=" + properties]
        before = dict(line.split("=", 1) for line in self.call(query).splitlines() if "=" in line)
        if before.get("ActiveState") not in ("inactive", "failed"):
            raise DeploymentError("RECOVERY_ALREADY_RUNNING")
        started = int(self.call(ssh + ["date", "+%s"]))
        self.call(ssh + ["sudo", "-n", "systemctl", "start", service], timeout=4800)
        after = dict(line.split("=", 1) for line in self.call(query).splitlines() if "=" in line)
        began = int(after.get("ExecMainStartTimestampMonotonic", 0))
        ended = int(after.get("ExecMainExitTimestampMonotonic", 0))
        if (after.get("Result") != "success" or after.get("ExecMainStatus") != "0" or after.get("ActiveState") != "inactive"
                or began <= int(before.get("ExecMainStartTimestampMonotonic", 0)) or ended < began):
            raise DeploymentError("FRESH_COMPLETE_RECOVERY_REQUIRED")
        status = json.loads(self.call(ssh + ["sudo", "-n", "cat", "/var/lib/smartupcms/recovery/status.json"]))
        finished = int(self.call(ssh + ["date", "+%s"]))
        point = status.get("lastSuccessPoint", 0)
        if (status.get("schemaVersion") != 1 or status.get("status") != "PASS" or not status.get("setId")
                or not started - 1 <= point <= finished + 1 or finished - point > 86400):
            raise DeploymentError("FRESH_COMPLETE_RECOVERY_REQUIRED")
        if status.get("sourceProject") != self.spec["project"] or status.get("sourceIdentity") != expected:
            raise DeploymentError("RECOVERY_SOURCE_MISMATCH")

    def recovery_request(self, payload):
        alias = self.spec.get("recovery_host", "")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,62}", alias):
            raise DeploymentError("RECOVERY_HOST_REQUIRED")
        content = json.dumps({"schemaVersion": 1, "project": self.spec["project"], **payload})
        if len(content.encode()) > 16 * 1024 * 1024:
            raise DeploymentError("RECOVERY_SNAPSHOT_TOO_LARGE")
        raw = self.call(["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", alias,
                         "sudo", "-n", "/usr/bin/python3", "/opt/smartupcms/deployment/install_recovery_snapshot.py"],
                        input=content, timeout=180)
        return json.loads(raw)

    def bind_recovery_source(self):
        identity = source_identity(self.spec["project"], self.call)
        previous = self.state.get("expectedRecoverySource")
        boundary = self.state.get("migrationBoundary", {})
        current = self.state.get("current", {})
        pending_migration = boundary and any(boundary.get(key) != current.get(key) for key in ("checksums_sha256", "identity"))
        if previous and (source_location(previous) != source_location(identity) or (not pending_migration and previous != identity)):
            raise DeploymentError("RECOVERY_SOURCE_MISMATCH")
        if previous and pending_migration and previous["services"]["backup"] != identity["services"]["backup"]:
            snapshot = contained(self.root / "configurations", self.root / "configurations" / boundary["identity"] / "compose.json")
            candidate = json.loads(private_file(snapshot))
            if candidate.get("services", {}).get("backup", {}).get("image") != identity["services"]["backup"]["image"]:
                raise DeploymentError("RECOVERY_PARTIAL_RELEASE_MISMATCH")
            installed = self.recovery_request({"compose": candidate, "expectedSource": identity})
            if installed.get("status") != "RECOVERY_SNAPSHOT_INSTALLED":
                raise DeploymentError("RECOVERY_SNAPSHOT_FAILED")
        result = self.recovery_request({"action": "preflight", "expectedSource": identity})
        if result.get("status") != "RECOVERY_SOURCE_VERIFIED" or result.get("sourceIdentity") != identity:
            raise DeploymentError("RECOVERY_SOURCE_MISMATCH")
        self.state["expectedRecoverySource"] = identity
        atomic_json(self.root / "state.json", self.state)
        return identity

    def synchronize_recovery(self):
        self.phase = "POSTDEPLOY_SYNC"
        self.state["pendingRecoverySync"] = {"identity": self.identity, "version": self.spec["version"]}
        self.record("RUNNING")
        identity = source_identity(self.spec["project"], self.call)
        result = self.recovery_request({"compose": self.rendered, "expectedSource": identity})
        if result.get("status") != "RECOVERY_SNAPSHOT_INSTALLED":
            raise DeploymentError("RECOVERY_SNAPSHOT_FAILED")
        self.state["expectedRecoverySource"] = identity
        self.state.pop("pendingRecoverySync", None)

    def apply(self, containers):
        self.phase = "PULL"
        future = self.recovery_request({"action": "validate-future", "compose": self.rendered,
                                        "daemonId": self.call(["docker", "info", "--format", "{{.ID}}"] )})
        if future.get("status") != "RECOVERY_FUTURE_CONFIG_VERIFIED":
            raise DeploymentError("RECOVERY_FUTURE_CONFIG_MISMATCH")
        self.call(self.compose + ["pull"])
        volume = self.rendered["volumes"]["postgres-data"]["name"]
        volumes = self.call(["docker", "volume", "ls", "--filter", "name=^" + re.escape(volume) + "$", "--format", "{{.Name}}"])
        if containers or volumes.strip():
            self.recover()
        self.phase = "QUIESCE"
        for name in ("server", "backup"):
            ids = [item["ID"] for item in containers if item["Service"] == name and item.get("State") == "running"]
            self.stopped += ids
            if ids:
                self.call(self.compose + ["stop", name])
        self.phase = "DEPLOYMENT"
        self.deployment_started = True
        self.record("RUNNING")
        env = {**self.process_env, "COMPOSE_FILE": str(self.compose_file), "ENV_FILE": str(self.env_file),
               "HEALTH_TIMEOUT_SECONDS": str(self.spec.get("health_timeout", 180)),
               "DEPLOYMENT_HISTORY_FILE": str(self.root / "deployment-history.jsonl")}
        self.run(["bash", "scripts/prod/deploy.sh"], cwd=str(self.bundle), env=env, timeout=7200)
        if not self.healthy(self.containers()):
            raise DeploymentError("DEPLOYMENT_NOT_HEALTHY")

    def record(self, status):
        self.state["lastAttempt"] = {"status": status, "phase": self.phase, "version": self.spec["version"],
                                     "checksums_sha256": self.spec["checksums_sha256"], "at": time.time()}
        if self.deployment_started:
            self.state["migrationBoundary"] = {"version": self.spec["version"], "checksums_sha256": self.spec["checksums_sha256"],
                                               "identity": self.identity}
        if status == "SUCCESS":
            previous = self.state.get("current")
            if previous:
                self.state.setdefault("previous", []).append(previous)
            self.state["current"] = {"identity": self.identity, "version": self.spec["version"],
                "release_channel": self.spec["release_channel"], "source_sha": self.spec["expected_source_sha"],
                "checksums_sha256": self.spec["checksums_sha256"], "compose": str(self.compose_file),
                "environment": str(self.env_file), "project": self.spec["project"]}
        atomic_json(self.root / "state.json", self.state)

    def rollback_plan(self):
        approval = json.loads(private_file(contained(self.incoming, self.spec["rollback_approval"])))
        target = next((item for item in self.state.get("previous", []) if item["version"] == approval.get("targetVersion")
                       and item["checksums_sha256"] == approval.get("targetChecksumsSha256")), None)
        boundary = self.state.get("migrationBoundary", self.state.get("current", {}))
        if (not target or not self.state.get("current") or approval.get("currentChecksumsSha256") != boundary.get("checksums_sha256")
                or approval.get("applicationRollbackTested") is not True or not approval.get("evidenceReference")):
            raise DeploymentError("TESTED_PREVIOUS_RELEASE_REQUIRED")
        argv = ["docker", "compose", "-p", target["project"], "-f", target["compose"], "--env-file", target["environment"]]
        atomic_json(self.root / "rollback-plan.json", {"executeSupported": False, "version": target["version"],
            "commands": [argv + ["pull", "server", "web"], argv + ["up", "-d", "--no-deps", "--wait", "server", "web"]],
            "requires": ["Repeat target release verification", "Approve current-schema compatibility", "Fresh complete recovery set"]})
        return {"changed": True, "status": "ROLLBACK_PLAN_ONLY", "version": target["version"]}

    def validate_history(self):
        attempt = self.state.get("lastAttempt", {})
        if attempt.get("status") == "RUNNING" and attempt.get("phase") == "DEPLOYMENT":
            raise DeploymentError("INTERRUPTED_DEPLOYMENT_REQUIRES_REVIEW")
        boundary = self.state.get("migrationBoundary")
        if boundary:
            desired = version_order(self.spec["version"])
            migrated = version_order(boundary["version"])
            if desired < migrated:
                raise DeploymentError("FAILED_MIGRATION_REQUIRES_FORWARD_RELEASE" if self.state.get("lastAttempt", {}).get("status") == "FAILED"
                                      else "DOWNGRADE_REQUIRES_ROLLBACK_PLAN")
            if desired == migrated and boundary["checksums_sha256"] != self.spec["checksums_sha256"]:
                raise DeploymentError("IMMUTABLE_VERSION_CHANGED")
        current = self.state.get("current")
        if current:
            if current["project"] != self.spec["project"]:
                raise DeploymentError("PROJECT_IDENTITY_CHANGED")
            if version_order(self.spec["version"]) < version_order(current["version"]):
                raise DeploymentError("DOWNGRADE_REQUIRES_ROLLBACK_PLAN")
            if current["version"] == self.spec["version"] and current["checksums_sha256"] != self.spec["checksums_sha256"]:
                raise DeploymentError("IMMUTABLE_VERSION_CHANGED")

    def execute(self, check=False):
        self.validate()
        if check:
            return {"changed": False, "status": "CHECK_ONLY"}
        private_directory(self.root)
        with FileLock(self.root / "deploy.lock"):
            state_file = self.root / "state.json"
            self.state = json.loads(private_file(state_file)) if state_file.exists() else {}
            if self.spec.get("action") == "rollback-plan":
                return self.rollback_plan()
            self.validate_history()
            try:
                self.prerequisites()
                self.verify_and_extract()
                self.configuration()
                containers = self.containers()
                pending = self.state.get("pendingRecoverySync")
                if pending:
                    if pending["identity"] != self.identity or not self.healthy(containers):
                        raise DeploymentError("PENDING_RECOVERY_SYNC_REQUIRED")
                    self.synchronize_recovery()
                    self.record("SUCCESS")
                    return {"changed": True, "status": "DEPLOYED", "version": self.spec["version"]}
                if self.state.get("current", {}).get("identity") == self.identity and self.healthy(containers):
                    return {"changed": False, "status": "UNCHANGED", "version": self.spec["version"]}
                self.apply(containers)
                self.synchronize_recovery()
                self.record("SUCCESS")
                return {"changed": True, "status": "DEPLOYED", "version": self.spec["version"]}
            except Exception:
                cleanup_failed = False
                try:
                    if self.phase == "POSTDEPLOY_SYNC":
                        pass
                    elif self.deployment_started:
                        self.call(self.compose + ["stop", "server", "backup"])
                    else:
                        for container in self.stopped:
                            self.call(["docker", "start", container])
                except DeploymentError:
                    cleanup_failed = True
                self.record("FAILED")
                if cleanup_failed:
                    raise DeploymentError("DEPLOYMENT_CLEANUP_FAILED") from None
                raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        spec = json.loads(private_file(Path(args.spec)))
        print(json.dumps(Deployment(spec).execute(check=args.check)))
    except DeploymentError as error:
        print(json.dumps({"status": "FAILED", "code": str(error)}))
        return 1
    except Exception:
        print(json.dumps({"status": "FAILED", "code": "INVALID_DEPLOYMENT_INPUT"}))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
