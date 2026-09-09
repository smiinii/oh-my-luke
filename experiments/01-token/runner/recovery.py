"""Durable resource ownership, with nonblocking locks protecting active runs.

An interrupted attempt is not resumed as if no time/tokens were spent. Cleanup
produces an interrupted record; any replacement is a separately named attempt.
"""
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import uuid

LABEL = "io.ohmyluke.lease"
DEFAULT_ROOT = Path(__file__).resolve().parents[1] / "local-runs" / "recovery"


def read_record(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > 16384:
            raise ValueError("Unsafe recovery record")
        record = json.loads(stream.read(16385))
    if not re.fullmatch(r"[a-f0-9]{32}", record.get("id", "")) or path.name != record["id"] + ".json":
        raise ValueError("Invalid recovery identity")
    return record


def persist(path, record):
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(record, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def lock_record(path):
    descriptor = os.open(path.with_suffix(".lock"), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    info = os.fstat(descriptor)
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        os.close(descriptor)
        raise ValueError("Unsafe recovery lock")
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(descriptor)
        return None
    return descriptor


def cleanup(record, docker, observed=None):
    base = "oml-prep-" + record["id"]
    errors = []
    for kind, name in (("container", base + "-export"), ("container", base), ("volume", base)):
        try:
            try:
                info = json.loads(docker(kind, "inspect", name))[0]
            except RuntimeError as error:
                # An unavailable daemon is NOT equivalent to a nonexistent resource.
                message = str(error).lower()
                if (("no such " + kind + ": " + name) in message
                        or kind == "volume" and ("get " + name + ": no such volume") in message):
                    continue
                raise
            labels = info["Config"].get("Labels", {}) if kind == "container" else info.get("Labels", {})
            if labels.get(LABEL) != record["id"]:
                raise RuntimeError("Recovery ownership mismatch: " + name)
            if observed is not None:
                observed.append(name)
            docker("rm", "--force", info["Id"]) if kind == "container" else docker("volume", "rm", name)
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
            errors.append(str(error)[:2000])
    return errors


def recover_abandoned(docker, root=DEFAULT_ROOT):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    if root.is_symlink():
        raise ValueError("Recovery root must not be a link")
    recovered = []
    for path in sorted(root.glob("*.json")):
        record = read_record(path)
        descriptor = lock_record(path)
        if descriptor is None:
            continue
        try:
            record = read_record(path)
            observed = []
            errors = cleanup(record, docker, observed)
            # Recheck terminal leases: a daemon may finish a create request after
            # the client crashed and an earlier recovery observed no resource.
            if record["state"] == "CLEANED" and not observed and not errors:
                continue
            record.update(state="CLEANUP_FAILED" if errors else "CLEANED", cleanupErrors=errors,
                          result="INTERRUPTED", totalTokens=None, allTokenUsageAvailable=False)
            persist(path, record)
            recovered.append(record)
            if errors:
                raise RuntimeError("Recovery incomplete; new worker admission denied: " + "; ".join(errors))
        finally:
            os.close(descriptor)
    return recovered


class Lease:
    def __init__(self, docker, root=DEFAULT_ROOT):
        root = Path(root)
        recover_abandoned(docker, root)
        self.id = uuid.uuid4().hex
        self.name = "oml-prep-" + self.id
        self.path = root / (self.id + ".json")
        self.descriptor = lock_record(self.path)
        self.record = {"id": self.id, "state": "ACTIVE"}
        try:
            persist(self.path, self.record)  # Commit intent before any Docker mutation.
        except BaseException:
            os.close(self.descriptor)
            raise

    def finish(self, errors):
        try:
            self.record.update(state="CLEANUP_FAILED" if errors else "CLEANED", cleanupErrors=errors)
            persist(self.path, self.record)
        finally:
            os.close(self.descriptor)
