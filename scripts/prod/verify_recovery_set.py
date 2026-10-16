"""Verify a complete recovery set before any isolated restore (NFR-DATA-04)."""
import argparse
import datetime as dt
import json
import re
from pathlib import Path

from recovery_support import SET_NAME, digest


def verify(directory, max_age_seconds=86400, now=None):
    directory = Path(directory)
    if not directory.is_absolute() or directory.is_symlink():
        raise ValueError("An absolute, non-symlink recovery directory is required")
    manifest = directory / "complete.json"
    if not manifest.is_file() or manifest.is_symlink():
        raise ValueError("The complete recovery manifest is missing")
    value = json.loads(manifest.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != 1 or not SET_NAME.fullmatch(value.get("setId", "")):
        raise ValueError("Invalid recovery manifest")
    point = dt.datetime.fromisoformat(value["consistentPoint"].replace("Z", "+00:00"))
    now = now or dt.datetime.now(dt.timezone.utc)
    age = (now - point).total_seconds()
    if not 0 <= age <= max_age_seconds:
        raise ValueError("Recovery point is in the future or exceeds the approved RPO")
    files = value.get("files", [])
    names = set()
    for item in files:
        name = item.get("name", "")
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", name) or name in names:
            raise ValueError("Unsafe or duplicate archive name")
        names.add(name)
        file = directory / name
        if not file.is_file() or file.is_symlink() or file.stat().st_size != item["bytes"] or digest(file) != item["sha256"]:
            raise ValueError("Recovery file is missing or corrupt")
    databases = [name for name in names if name.endswith(".dump.age")]
    objects = [name for name in names if name.startswith("smartupcms-objects-") and name.endswith(".tar.age")]
    if len(databases) != 2 or len(objects) != 1:
        raise ValueError("CMS, DWH and one object archive are mandatory")
    for prefix in ("smartupcms-", "smartupcms_dwh-"):
        matching = [name for name in databases if name.startswith(prefix)]
        if len(matching) != 1:
            raise ValueError("A database archive is missing")
        name = matching[0]
        meta_name = name + ".manifest.json"
        if meta_name not in names:
            raise ValueError("Database metadata is missing")
        metadata = json.loads((directory / meta_name).read_text())
        if metadata.get("capturedAt") != value["consistentPoint"] or metadata.get("archiveSha256") != digest(directory / name):
            raise ValueError("Database is not bound to this consistent recovery point")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--max-age-seconds", type=int, default=86400)
    args = parser.parse_args()
    try:
        if not 1 <= args.max_age_seconds <= 86400:
            raise ValueError("RPO limit cannot exceed the approved 24 hours")
        verify(args.directory, args.max_age_seconds)
    except (ValueError, KeyError, TypeError, OSError):
        print("Recovery set verification failed.")
        return 1
    print("Complete recovery set verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
