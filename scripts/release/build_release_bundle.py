"""Build both release archives and a checksummed source/channel manifest."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import tarfile
import zipfile

from release_policy import COMPONENTS, git, release_version

FILES = (
    "deploy/compose/docker-compose.prod.yml",
    "README.md", "LICENSE", "NOTICE", "SECURITY.md", "SUPPORT.md", "CHANGELOG.md",
    "CODE_OF_CONDUCT.md", "CONTRIBUTING.md", "GOVERNANCE.md", "RELICENSE.md",
    *["scripts/prod/" + name for name in (
        "deploy.sh", "backup.sh", "restore.sh", "deploy.ps1", "backup.ps1", "restore.ps1",
        "backup-objects.ps1", "restore-combined.ps1", "rollback.sh", "rollback.ps1", "test-recovery.ps1",
        "recovery_coordinator.py", "recovery_support.py", "verify_recovery_set.py")],
    *["scripts/acceptance/" + name for name in (
        "invoke-managed-preflight.ps1", "invoke-managed-host-check.ps1", "run-capacity.ps1", "run-failure-scenario.ps1")],
    "scripts/release/verify-published-release.ps1", "scripts/release/test-release-gates.ps1",
)
TREES = ("deploy/compose", "deploy/staging", "deploy/observability", "deploy/recovery", "deploy/ansible",
         "deploy/acceptance", "scripts/acceptance/load", "docs")
ENVIRONMENTS = ("deploy/compose/.env.example", "deploy/staging/.env.staging.example",
                "deploy/acceptance/.env.managed.example", "deploy/staging/.env.acceptance.example")
SUFFIXES = ("image.txt", "spdx.json", "cdx.json", "provenance.json")


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def metadata(output, version, repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid release repository")
    names = [f"smartupcms-{component}-{version}.{suffix}" for component in COMPONENTS for suffix in SUFFIXES]
    if {item.name for item in output.iterdir()} != set(names):
        raise ValueError("Output must contain exactly the six complete image metadata sets; reuse is forbidden")
    images = []
    for component in COMPONENTS:
        for suffix in SUFFIXES:
            path = output / f"smartupcms-{component}-{version}.{suffix}"
            if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
                raise ValueError("Image metadata must be nonempty regular files")
        image = (output / f"smartupcms-{component}-{version}.image.txt").read_text().strip()
        prefix = "ghcr.io/" + repository.split("/")[0].lower() + "/smartupcms/" + component
        if not re.fullmatch(re.escape(prefix) + r"@sha256:[a-f0-9]{64}", image):
            raise ValueError("Unexpected immutable component image reference")
        images.append(image)
    return names, images


def copy_sources(root, bundle):
    tracked = git(root, "ls-files", "-z").split("\0")
    selected = {name for name in tracked if name in FILES or any(name.startswith(tree + "/") for tree in TREES)}
    if not set(FILES).issubset(selected) or not set(ENVIRONMENTS).issubset(selected):
        raise ValueError("Tracked release bundle sources are incomplete")
    if "deploy/compose/docker-compose.prod.yml" not in selected:
        raise ValueError("Production Compose is missing")
    for name in selected:
        source = root / name
        if not source.is_file() or source.is_symlink() or not source.resolve().is_relative_to(root.resolve()):
            raise ValueError("Release sources must be contained regular files")
        destination = bundle / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        destination.chmod(0o755 if name.endswith(".sh") else 0o644)
    shutil.copyfile(bundle / "deploy/compose/docker-compose.prod.yml", bundle / "docker-compose.yml")
    shutil.copyfile(bundle / ENVIRONMENTS[0], bundle / ".env.example")


def populate_environment(path, images, version, repository):
    value = path.read_text(encoding="utf-8")
    acceptance = "acceptance" in path.name or "managed" in path.name
    replacements = {("ACCEPTANCE_EXPECTED_" if acceptance else "") + component.upper() + "_IMAGE": image
                    for component, image in zip(COMPONENTS, images)}
    for name, replacement in replacements.items():
        value, count = re.subn(r"(?m)^" + name + r"=.*$", name + "=" + replacement, value)
        if count != 1:
            raise ValueError("Image environment declaration must occur exactly once: " + name)
    for name, replacement in (("APP_VERSION", version), ("IMAGE_REGISTRY", "ghcr.io/" + repository.split("/")[0].lower() + "/smartupcms")):
        value = re.sub(r"(?m)^" + name + r"=.*$", name + "=" + replacement, value)
    path.write_text(value, encoding="utf-8", newline="\n")


def archive_mode(name):
    return 0o755 if name.endswith(".sh") else 0o644


def portable_tar_entry(info):
    info.mode = archive_mode(info.name)
    info.uid, info.gid, info.uname, info.gname = 0, 0, "", ""
    return info


def build(root, *, channel, version, source_sha, repository, output):
    release_version(channel, version)
    if not re.fullmatch(r"[a-f0-9]{40}", source_sha) or git(root, "rev-parse", "HEAD") != source_sha:
        raise ValueError("Source SHA must match the checked-out commit")
    if git(root, "status", "--porcelain", "--untracked-files=no"):
        raise ValueError("Release source must have no tracked changes")
    output = output.resolve()
    if output.is_symlink() or not output.is_dir():
        raise ValueError("An existing metadata output directory is required")
    names, images = metadata(output, version, repository)
    manifest = dict(schemaVersion=1, channel=channel, version=version, sourceSha=source_sha, images=images)
    encoded = json.dumps(manifest, indent=2) + "\n"
    bundle = output / ("smartupcms-" + version)
    bundle.mkdir()
    copy_sources(root, bundle)
    for path in (".env.example", *ENVIRONMENTS):
        populate_environment(bundle / path, images, version, repository)
    (bundle / "REVISION").write_text(source_sha + "\n", encoding="ascii")
    (bundle / "IMAGES.txt").write_text("\n".join(sorted(images)) + "\n", encoding="ascii")
    (bundle / "release.json").write_text(encoded, encoding="utf-8", newline="\n")
    (output / "release.json").write_text(encoded, encoding="utf-8", newline="\n")
    files = sorted(path for path in bundle.rglob("*") if path.is_file())
    with tarfile.open(output / (bundle.name + ".tar.gz"), "w:gz") as archive:
        for path in files:
            archive.add(path, arcname=path.relative_to(output).as_posix(), recursive=False, filter=portable_tar_entry)
    with zipfile.ZipFile(output / (bundle.name + ".zip"), "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            entry = zipfile.ZipInfo.from_file(path, arcname=path.relative_to(output).as_posix())
            entry.create_system = 3
            entry.external_attr = (0o100000 | archive_mode(entry.filename)) << 16
            archive.writestr(entry, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    names += ["release.json", bundle.name + ".tar.gz", bundle.name + ".zip"]
    (output / "SHA256SUMS").write_text("".join(digest(output / name) + "  " + name + "\n" for name in sorted(names)), encoding="ascii")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("channel", "version", "source-sha", "repository", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        build(Path.cwd(), channel=args.channel, version=args.version, source_sha=args.source_sha,
              repository=args.repository, output=Path(args.output))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
