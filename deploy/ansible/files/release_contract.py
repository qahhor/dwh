"""Private files, immutable bundles and process locking for deployment."""
import hashlib
import hmac
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile


class DeploymentError(RuntimeError):
    """Only fixed failure codes may cross the runner's logging boundary."""


class FileLock:
    def __init__(self, path):
        self.path = path

    def __enter__(self):
        self.handle = open(self.path, "a+b")
        os.chmod(self.path, 0o600)
        try:
            if os.name == "nt":
                import msvcrt
                self.handle.seek(0)
                msvcrt.locking(self.handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.handle.close()
            raise DeploymentError("DEPLOYMENT_LOCKED") from None
        return self

    def __exit__(self, *args):
        self.handle.close()


def command(argv, *, cwd=None, env=None, timeout=7200, input=None):
    try:
        result = subprocess.run(argv, cwd=cwd, env=env, timeout=timeout, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, input=input)
    except (OSError, subprocess.TimeoutExpired):
        raise DeploymentError("COMMAND_FAILED") from None
    if result.returncode:
        raise DeploymentError("COMMAND_FAILED")
    return result.stdout.strip()


def contained(root, value):
    path = Path(value)
    if not path.is_absolute() or not path.resolve().is_relative_to(root.resolve()) or path.resolve() == root.resolve():
        raise DeploymentError("UNSAFE_PATH")
    if any(item.is_symlink() for item in (path, *path.parents)):
        raise DeploymentError("UNSAFE_PATH")
    return path


def private_file(path):
    if not path.is_file() or path.is_symlink():
        raise DeploymentError("PRIVATE_FILE_REQUIRED")
    if os.name != "nt" and (path.stat().st_mode & 0o077 or path.stat().st_uid != os.geteuid()):
        raise DeploymentError("PRIVATE_FILE_PERMISSIONS")
    return path.read_bytes()


def private_directory(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


def atomic_bytes(path, content):
    private_directory(path.parent)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=".new-")
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def atomic_json(path, value):
    atomic_bytes(path, json.dumps(value, sort_keys=True, indent=2).encode())


def own_secret(path, uid):
    if os.name != "nt":
        os.chown(path, uid, uid)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def tree_identity(root):
    result = []
    for item in sorted(root.rglob("*")):
        if item.is_symlink() or not (item.is_file() or item.is_dir()):
            raise DeploymentError("UNSAFE_PATH")
        if item.is_file():
            result.append((item.relative_to(root).as_posix(), digest(item)))
    return result


def private_identity(key, inputs):
    mac = hmac.new(key, digestmod=hashlib.sha256)
    for name, value in inputs:
        for part in (name.encode(), value):
            mac.update(len(part).to_bytes(8, "big"))
            mac.update(part)
    return mac.hexdigest()


def safe_extract(source, destination, version):
    prefix = "smartupcms-" + version
    with tarfile.open(source, "r:gz") as archive:
        entries = archive.getmembers()
        seen = set()
        if len(entries) > 100000 or sum(item.size for item in entries) > 512 * 1024 * 1024:
            raise DeploymentError("UNSAFE_ARCHIVE")
        for item in entries:
            name = PurePosixPath(item.name)
            if (name.is_absolute() or ".." in name.parts or "\\" in item.name
                    or not name.parts or name.parts[0] != prefix or name.as_posix() in seen
                    or not (item.isfile() or item.isdir())):
                raise DeploymentError("UNSAFE_ARCHIVE")
            seen.add(name.as_posix())
        private_directory(destination)
        for item in entries:
            target = contained(destination, destination / item.name)
            if item.isdir():
                private_directory(target)
            else:
                private_directory(target.parent)
                with archive.extractfile(item) as source_file, target.open("wb") as output:
                    shutil.copyfileobj(source_file, output)
                target.chmod(0o700 if item.mode & 0o100 else 0o600)
    return destination / prefix
