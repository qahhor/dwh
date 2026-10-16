"""Bounded, secret-safe host operations for NFR-DATA-04 recovery sets."""
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlparse

SET_NAME = re.compile(r"^set-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}$")


def container_environment(container):
    return dict(value.split("=", 1) for value in container["Config"].get("Env", []) if "=" in value)


def inspect_sources(project, run):
    daemon = run(["docker", "info", "--format", "{{.ID}}"])
    if not daemon or not re.fullmatch(r"[A-Za-z0-9:_-]+", daemon):
        raise RuntimeError("Recovery Docker daemon identity is unavailable")
    containers = {}
    for service in ("server", "backup", "postgres"):
        ids = run(["docker", "ps", "-a", "--filter", "label=com.docker.compose.project=" + project,
                   "--filter", "label=com.docker.compose.service=" + service, "--format", "{{.ID}}"]).split()
        if len(ids) != 1:
            raise RuntimeError("Exactly one existing " + service + " source is required")
        values = json.loads(run(["docker", "inspect", ids[0]]))
        if len(values) != 1:
            raise RuntimeError("Recovery source inspection is ambiguous")
        container = values[0]
        labels = container["Config"].get("Labels", {})
        if (labels.get("com.docker.compose.project") != project
                or labels.get("com.docker.compose.service") != service
                or not labels.get("com.docker.compose.config-hash")):
            raise RuntimeError("Recovery source Compose labels do not match")
        if container["State"]["Status"] not in ("running", "exited", "created"):
            raise RuntimeError("Recovery source has an unstable container state")
        containers[service] = container
    return daemon, containers


def object_source(container):
    env = container_environment(container)
    provider = env.get("SMC_PROVIDER_STORAGE")
    if provider == "s3":
        source = {"provider": provider, "endpoint": env.get("SMC_S3_ENDPOINT", "").rstrip("/"),
                  "bucket": env.get("SMC_S3_BUCKET"), "region": env.get("SMC_S3_REGION", "auto")}
        validate_remote(source)
        return source
    if provider == "local_disk":
        target = env.get("SMC_STORAGE_LOCAL_PATH")
        mounts = [item for item in container.get("Mounts", []) if item.get("Destination") == target]
        if len(mounts) == 1 and mounts[0].get("Type") == "bind":
            return {"provider": provider, "localStoragePath": mounts[0]["Source"]}
    raise RuntimeError("Recovery object source cannot be bound to the application")


def source_identity(project, run):
    daemon, containers = inspect_sources(project, run)
    backup = container_environment(containers["backup"])
    server = container_environment(containers["server"])
    databases = {key: backup.get(key) for key in ("PGHOST", "PGDATABASE", "WAREHOUSE_DB_NAME")}
    if (databases["PGHOST"] != "postgres" or not databases["PGDATABASE"] or not databases["WAREHOUSE_DB_NAME"]
            or server.get("DB_URL") != "jdbc:postgresql://postgres:5432/" + databases["PGDATABASE"]
            or server.get("WAREHOUSE_URL") != "jdbc:postgresql://postgres:5432/" + databases["WAREHOUSE_DB_NAME"]):
        raise RuntimeError("Recovery database source does not match the application")
    mounts = [{key: item.get(key, "") for key in ("Type", "Name", "Source", "Destination")}
              for item in containers["postgres"].get("Mounts", [])
              if item.get("Destination", "").startswith("/var/lib/postgresql")]
    if len(mounts) != 1 or mounts[0]["Type"] not in ("volume", "bind"):
        raise RuntimeError("Recovery PostgreSQL storage identity is ambiguous")
    services = {name: {"image": item["Config"]["Image"],
                       "configHash": item["Config"]["Labels"]["com.docker.compose.config-hash"],
                       "networks": sorted(item.get("NetworkSettings", {}).get("Networks", {}))}
                for name, item in containers.items()}
    return {"schemaVersion": 1, "daemonId": daemon, "project": project, "services": services,
            "postgresMounts": mounts, "databases": databases, "objects": object_source(containers["server"]),
            "ageRecipient": backup.get("AGE_RECIPIENT")}


def source_location(identity):
    return {key: value for key, value in identity.items() if key != "services"}


def verify_source_settings(config, identity):
    if config.get("ageRecipient") != identity.get("ageRecipient"):
        raise RuntimeError("Recovery encryption recipient does not match the application")
    objects = config["objects"]
    required = {key: objects.get(key, "auto" if key == "region" else None) for key in identity["objects"]}
    if required.get("endpoint"):
        required["endpoint"] = required["endpoint"].rstrip("/")
    if required != identity["objects"]:
        raise RuntimeError("Recovery object source configuration does not match the application")


def verify_source(config, compose, run, expected=None):
    if config["objects"]["provider"] == "local_disk" and os.environ.get("DOCKER_HOST", "").startswith("ssh://"):
        raise RuntimeError("Remote local-disk object capture is unsupported")
    identity = source_identity(config["project"], run)
    approved = expected if expected is not None else config.get("expectedSource")
    if not approved or identity != approved:
        raise RuntimeError("Recovery source identity does not match the approved application")
    verify_source_settings(config, identity)
    rendered = json.loads(run(compose + ["--profile", "tools", "config", "--format", "json"]))
    verify_capture_settings(config, rendered)
    backup = rendered.get("services", {}).get("backup", {})
    env = backup.get("environment", {})
    if (rendered.get("name") != config["project"] or backup.get("image") != identity["services"]["backup"]["image"]
            or any(env.get(key) != value for key, value in identity["databases"].items())):
        raise RuntimeError("Recovery Compose configuration does not match the current backup source")
    networks = sorted(rendered.get("networks", {}).get(name, {}).get("name", "") for name in backup.get("networks", {}))
    if (not networks or networks != identity["services"]["backup"]["networks"]
            or not set(networks).intersection(identity["services"]["postgres"]["networks"])):
        raise RuntimeError("Recovery backup networks do not match the inspected database source")
    return identity


def verify_capture_settings(config, rendered):
    services = rendered.get("services", {})
    server = services.get("server", {})
    container = {"Config": {"Env": [str(key) + "=" + str(value) for key, value in server.get("environment", {}).items()]},
                 "Mounts": [{"Type": item.get("type"), "Source": item.get("source"), "Destination": item.get("target")}
                            for item in server.get("volumes", [])]}
    source = object_source(container)
    objects = config["objects"]
    expected = {key: objects.get(key, "auto" if key == "region" else None) for key in source}
    if expected.get("endpoint"):
        expected["endpoint"] = expected["endpoint"].rstrip("/")
    recipient = services.get("backup", {}).get("environment", {}).get("AGE_RECIPIENT")
    if source != expected or not recipient or recipient != config.get("ageRecipient"):
        raise RuntimeError("Future application storage or encryption is not prepared for recovery")


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def private_directory(path):
    path = Path(path)
    if path.is_symlink() or any(p.is_symlink() for p in path.parents):
        raise ValueError("Recovery directories must not traverse symlinks")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if os.name != "nt":
        path.chmod(0o700)
    return path


def atomic_json(path, value):
    path = Path(path)
    private_directory(path.parent)
    temporary = path.with_name(path.name + ".partial")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=True, indent=2)
        stream.write("\n")
    if os.name != "nt":
        temporary.chmod(0o600)
    temporary.replace(path)


def command(args, timeout=120, env=None):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=env, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError("Required command unavailable or timed out") from None
    if result.returncode:
        # Tool stderr may contain configuration values; it is never returned to logs.
        raise RuntimeError("External operation failed; no recovery success was recorded")
    return result.stdout.strip()


def secret_environment(settings):
    env = os.environ.copy()
    for setting, variable in (("accessKeyFile", "AWS_ACCESS_KEY_ID"), ("secretKeyFile", "AWS_SECRET_ACCESS_KEY")):
        file = Path(settings[setting])
        if file.is_symlink() or (os.name != "nt" and file.stat().st_mode & 0o077):
            raise ValueError("Credential files require private permissions and no symlinks")
        value = file.read_text(encoding="utf-8").strip()
        if not value or "\n" in value:
            raise ValueError("Credential file is empty or malformed")
        env[variable] = value
    env["AWS_DEFAULT_REGION"] = settings.get("region", "auto")
    return env


def validate_remote(settings):
    endpoint = urlparse(settings.get("endpoint", ""))
    if endpoint.scheme != "https" or not endpoint.hostname or endpoint.username or endpoint.query or endpoint.fragment:
        raise ValueError("Storage endpoint must use HTTPS without embedded credentials")
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", settings.get("bucket", "")):
        raise ValueError("Invalid storage bucket")


def validate_config(config):
    if config.get("schemaVersion") != 1 or not re.fullmatch(r"[a-z][a-z0-9-]{2,48}", config.get("project", "")):
        raise ValueError("Invalid recovery configuration version/project")
    for key in ("outputDirectory", "envFile", "metricsFile"):
        path = Path(config.get(key, ""))
        if not path.is_absolute() or ".." in path.parts or path == Path(path.anchor):
            raise ValueError("Recovery paths must be absolute non-root paths")
    compose = config.get("composeFiles", [])
    if not compose or any(not Path(p).is_absolute() or not Path(p).is_file() for p in compose):
        raise ValueError("Explicit existing Compose files are required")
    if not Path(config["envFile"]).is_file():
        raise ValueError("Private environment file is missing")
    for key, lower, upper in (("maxDurationSeconds", 60, 3600), ("maxLocalSets", 1, 4),
                              ("minFreeBytes", 1, 10**15), ("minFreePercent", 1, 90)):
        value = config.get(key)
        if type(value) is not int or not lower <= value <= upper:
            raise ValueError("Invalid recovery resource limit: " + key)
    if not config.get("ageRecipient", "").startswith("age1"):
        raise ValueError("An age public recipient is required")
    remote = config.get("recovery", {})
    validate_remote(remote)
    if not re.fullmatch(r"[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*", remote.get("prefix", "")):
        raise ValueError("Invalid recovery prefix")
    objects = config.get("objects", {})
    if objects.get("provider") not in ("s3", "local_disk"):
        raise ValueError("Explicit source object provider is required")
    for settings in (remote, objects) if objects["provider"] == "s3" else (remote,):
        validate_remote(settings)
        for key in ("accessKeyFile", "secretKeyFile"):
            path = Path(settings.get(key, ""))
            if not path.is_absolute() or not path.is_file():
                raise ValueError("Explicit existing credential file is required")
    if objects["provider"] == "local_disk":
        source = Path(objects.get("localStoragePath", ""))
        if not source.is_absolute() or not source.is_dir():
            raise ValueError("Local object storage requires an existing absolute directory")
        output = Path(config["outputDirectory"]).resolve()
        source = source.resolve()
        if output == source or source in output.parents or output in source.parents:
            raise ValueError("Object storage and recovery output must be separate trees")
    return config


def prune_sets(root, keep):
    """Delete only self-described, complete sets below this exact private root."""
    root = Path(root).resolve()
    sets = []
    for directory in root.iterdir():
        if not SET_NAME.fullmatch(directory.name) or directory.is_symlink() or not directory.is_dir():
            continue
        manifest = directory / "complete.json"
        if not manifest.is_file() or manifest.is_symlink():
            continue
        value = json.loads(manifest.read_text(encoding="utf-8"))
        if value.get("schemaVersion") == 1 and value.get("setId") == directory.name:
            sets.append(directory)
    for directory in sorted(sets, reverse=True)[keep:]:
        if directory.parent != root or any(p.is_symlink() for p in directory.rglob("*")):
            raise ValueError("Unsafe recovery cache entry")
        shutil.rmtree(directory)


def metrics(path, state):
    path = Path(path)
    if path.is_symlink() or any(p.is_symlink() for p in path.parents):
        raise ValueError("Metrics must not traverse symlinks")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".partial")
    values = {
        "smc_recovery_point_timestamp_seconds": state.get("lastSuccessPoint", 0),
        "smc_recovery_last_attempt_success": int(state["status"] == "PASS"),
        "smc_recovery_duration_seconds": state.get("durationSeconds", 0),
        "smc_recovery_pause_seconds": state.get("pauseSeconds", 0),
        "smc_recovery_last_attempt_timestamp_seconds": state["attemptTimestamp"],
    }
    temporary.write_text("".join(f"{name} {value}\n" for name, value in values.items()), encoding="ascii")
    if os.name != "nt":
        temporary.chmod(0o644)
    temporary.replace(path)
