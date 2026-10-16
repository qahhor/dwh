"""Publish complete authenticated assets through an explicit release channel."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

from release_policy import COMPONENTS, check_context, release_version


def assets(version):
    return ["SHA256SUMS", "SHA256SUMS.sigstore.json", "SHA256SUMS.provenance.json", "release.json",
            "smartupcms-" + version + ".tar.gz", "smartupcms-" + version + ".zip",
            *[f"smartupcms-{component}-{version}.{suffix}" for component in COMPONENTS
              for suffix in ("image.txt", "spdx.json", "cdx.json", "provenance.json")]]


def publish(root, channel, version, output, run=subprocess.run):
    release_version(channel, version)
    if os.environ.get("GITHUB_REF_NAME") != version:
        raise ValueError("Publication version differs from the event ref")
    check_context(root, channel, write_only=True)
    paths = [output / name for name in assets(version)]
    if any(not path.is_file() or path.is_symlink() or path.stat().st_size == 0 for path in paths):
        raise ValueError("Publication requires all archives, metadata and authenticated checksums")
    command = ["gh", "release", "create", version, *map(str, paths), "--verify-tag",
               "--title", "SmartupCMS " + version, "--generate-notes"]
    if channel == "staging":
        command += ["--prerelease", "--latest=false"]
    run(command, cwd=root, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("channel", "version", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        publish(Path.cwd(), args.channel, args.version, Path(args.output))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
