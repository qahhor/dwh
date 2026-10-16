"""Validate immutable release refs before CI and before publishing jobs."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

COMPONENTS = ("server", "web", "backup", "postgres", "typesense", "clamav")
STABLE = r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"


def release_version(channel, version):
    pattern = STABLE if channel == "stable" else STABLE + r"-rc\.[1-9][0-9]*"
    if channel not in ("stable", "staging") or not re.fullmatch(pattern, version):
        raise ValueError("Version does not match the selected release channel")
    return version


def git(root, *args):
    return subprocess.check_output(["git", "--no-replace-objects", "-C", str(root), *args],
                                   text=True, stderr=subprocess.PIPE).strip()


def check_context(root, channel, env=None, write_only=False):
    env = os.environ if env is None else env
    version = release_version(channel, env.get("GITHUB_REF_NAME", ""))
    if (env.get("GITHUB_EVENT_NAME") != "push" or env.get("GITHUB_REF_TYPE") != "tag"
            or env.get("GITHUB_REF") != "refs/tags/" + version):
        raise ValueError("Only an exact release tag push is permitted")
    if env.get("GITHUB_RUN_ATTEMPT") != "1":
        raise ValueError("Release reruns are forbidden; create a new reviewed version")
    sha = env.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("An immutable event source SHA is required")
    if git(root, "rev-parse", "HEAD") != sha or git(root, "rev-parse", "refs/tags/" + version + "^{commit}") != sha:
        raise ValueError("Event SHA, checkout HEAD and release tag must agree")
    if write_only:
        if channel == "staging" and (env.get("STAGING_APPROVED_SOURCE_SHA") != sha
                                      or env.get("STAGING_APPROVED_VERSION") != version):
            raise ValueError("Staging environment approval must name the exact source SHA and version")
        return sha
    branch = env.get("DEFAULT_BRANCH", "")
    if not branch or subprocess.run(["git", "check-ref-format", "refs/heads/" + branch], capture_output=True).returncode:
        raise ValueError("An explicit valid default branch is required")
    baseline = git(root, "rev-parse", "refs/remotes/origin/" + branch + "^{commit}")
    if channel == "stable":
        result = subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", sha, baseline], capture_output=True)
        if result.returncode:
            raise ValueError("Stable release commit must be reachable from the default branch")
    commits = git(root, "rev-list", "--reverse", baseline + ".." + sha).splitlines()
    for commit in dict.fromkeys([*commits, sha]):
        active = subprocess.run(["git", "-C", str(root), "cat-file", "-e", commit + "^:DCO"], capture_output=True)
        if active.returncode == 0 and not re.search(r"^Signed-off-by: .+ <[^<>\s]+@[^<>\s]+>$", git(root, "show", "-s", "--format=%B", commit), re.MULTILINE):
            raise ValueError("DCO sign-off is missing in the release commit range")
    return sha


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", choices=("stable", "staging"), required=True)
    parser.add_argument("--write-only", action="store_true")
    args = parser.parse_args()
    try:
        check_context(Path.cwd(), args.channel, write_only=args.write_only)
        print("Release source, channel and first-attempt policy passed.")
    except (ValueError, subprocess.CalledProcessError) as error:
        print(str(error) if isinstance(error, ValueError) else "Release Git identity could not be verified", file=sys.stderr)
        raise SystemExit(1)
