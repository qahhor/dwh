"""Install a bounded private Compose snapshot on the prepared recovery host."""
import json
import os
from pathlib import Path
import re
import sys

from release_contract import DeploymentError, atomic_bytes, atomic_json, command, private_file
from recovery_support import source_identity, verify_capture_settings, verify_source, verify_source_settings

MAX_INPUT_BYTES = 16 * 1024 * 1024
COMPONENTS = ("server", "web", "backup", "postgres", "typesense", "clamav")


def runtime_environment(config_path):
    env = os.environ.copy()
    text = private_file(config_path.parent / "recovery-runtime.env").decode()
    entries = dict(line.split("=", 1) for line in text.splitlines() if line and not line.startswith("#") and "=" in line)
    host = entries.get("DOCKER_HOST", "").strip().strip('"').strip("'")
    if not re.fullmatch(r"ssh://[A-Za-z0-9][A-Za-z0-9_-]{0,62}", host):
        raise DeploymentError("RECOVERY_DOCKER_HOST_REQUIRED")
    env.pop("DOCKER_CONTEXT", None)
    env["DOCKER_HOST"] = host
    return env


def perform(payload, config, config_path, run):
    if payload.get("project") != config.get("project"):
        raise DeploymentError("RECOVERY_PROJECT_MISMATCH")
    if config.get("objects", {}).get("provider") != "s3":
        raise DeploymentError("REMOTE_LOCAL_OBJECT_CAPTURE_UNSUPPORTED")
    action = payload.get("action", "install")
    if action == "validate-future":
        if run(["docker", "info", "--format", "{{.ID}}"] ) != payload.get("daemonId"):
            raise DeploymentError("RECOVERY_DAEMON_MISMATCH")
        verify_capture_settings(config, validate(payload, config))
        return {"status": "RECOVERY_FUTURE_CONFIG_VERIFIED"}
    if action == "preflight":
        compose = ["docker", "compose", "-p", config["project"]]
        for file in config["composeFiles"]:
            compose += ["-f", file]
        compose += ["--env-file", config["envFile"]]
        identity = verify_source(config, compose, run, payload.get("expectedSource"))
        config["expectedSource"] = identity
        atomic_json(config_path, config)
        return {"status": "RECOVERY_SOURCE_VERIFIED", "sourceIdentity": identity}
    if action != "install":
        raise DeploymentError("INVALID_ACTION")
    compose = validate(payload, config)
    verify_capture_settings(config, compose)
    identity = source_identity(config["project"], run)
    if identity != payload.get("expectedSource"):
        raise DeploymentError("RECOVERY_SOURCE_MISMATCH")
    verify_source_settings(config, identity)
    compose_path = config_path.parent / "recovery-current.compose.json"
    env_path = config_path.parent / "recovery-empty.env"
    atomic_bytes(env_path, b"")
    atomic_json(compose_path, compose)
    config.update(composeFiles=[str(compose_path)], envFile=str(env_path), expectedSource=identity)
    atomic_json(config_path, config)
    return {"status": "RECOVERY_SNAPSHOT_INSTALLED"}


def validate(payload, config):
    compose = payload.get("compose", {})
    if (payload.get("schemaVersion") != 1 or not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", payload.get("project", ""))
            or payload["project"] != config.get("project") or compose.get("name") != config["project"]):
        raise DeploymentError("RECOVERY_PROJECT_MISMATCH")
    services = compose.get("services", {})
    if any(name not in services for name in COMPONENTS):
        raise DeploymentError("RECOVERY_COMPOSE_INVALID")
    for service in services.values():
        if "build" in service or not re.fullmatch(r"[^\s]+@sha256:[a-f0-9]{64}", service.get("image", "")):
            raise DeploymentError("RECOVERY_COMPOSE_INVALID")
    for name in ("server", "backup", "migrate", "backup-bootstrap"):
        if services.get(name, {}).get("read_only") is not True:
            raise DeploymentError("RECOVERY_COMPOSE_INVALID")
    if (services["migrate"]["image"] != services["server"]["image"]
            or services["backup-bootstrap"]["image"] != services["backup"]["image"]):
        raise DeploymentError("RECOVERY_COMPOSE_INVALID")
    return compose


def install(raw, config_path=Path("/etc/smartupcms/recovery.json"),
            recovery_root=Path("/var/lib/smartupcms/recovery"), run=command):
    if len(raw) > MAX_INPUT_BYTES:
        raise DeploymentError("RECOVERY_SNAPSHOT_TOO_LARGE")
    config = json.loads(private_file(config_path))
    if config.get("outputDirectory") != str(recovery_root):
        raise DeploymentError("RECOVERY_OUTPUT_PATH_MISMATCH")
    if any(path.is_symlink() for path in (config_path, *config_path.parents, recovery_root, *recovery_root.parents)):
        raise DeploymentError("UNSAFE_PATH")
    payload = json.loads(raw)
    env = runtime_environment(config_path)
    remote = lambda args: run(args, env=env)
    lock = recovery_root / ".recovery.lock"
    try:
        descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise DeploymentError("RECOVERY_ALREADY_RUNNING") from None
    os.close(descriptor)
    try:
        active = run(["systemctl", "show", "smartupcms-recovery.service", "--property=ActiveState", "--value"])
        if active.strip() not in ("inactive", "failed"):
            raise DeploymentError("RECOVERY_ALREADY_RUNNING")
        return perform(payload, config, config_path, remote)
    finally:
        lock.unlink()


def main():
    os.umask(0o077)
    try:
        raw = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
        print(json.dumps(install(raw)))
        return 0
    except DeploymentError as error:
        print(json.dumps({"status": "FAILED", "code": str(error)}))
    except Exception:
        print(json.dumps({"status": "FAILED", "code": "RECOVERY_SNAPSHOT_INVALID"}))
    return 1


if __name__ == "__main__":
    sys.exit(main())
