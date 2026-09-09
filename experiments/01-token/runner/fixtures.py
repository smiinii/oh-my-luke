"""Deterministic independent fixtures; reference code is never copied to workers."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
TASKS = ("a", "b", "c", "pilot")


def task_spec(task):
    if task not in TASKS:
        raise ValueError("Unknown task")
    return json.loads((ROOT / "tasks" / f"{task}.json").read_text())


def copy_overlay(source, target):
    if not source.exists():
        return
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            raise ValueError("Fixture symlinks are not allowed")
        if path.is_file():
            destination = target / path.relative_to(source)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)


def snapshot(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Candidate root must be a real directory")
    result = {}
    pending, entries, total = [root], 0, 0
    while pending:
        directory = pending.pop()
        with os.scandir(directory) as children:
            for child in children:
                relative = Path(child.path).relative_to(root)
                if len(relative.parts) == 1 and child.name in (".git", ".gradle", "build"):
                    continue
                entries += 1
                if entries > 2000 or len(relative.parts) > 32:
                    raise ValueError("Fixture tree limit")
                info = child.stat(follow_symlinks=False)
                if stat.S_ISDIR(info.st_mode):
                    pending.append(Path(child.path))
                    continue
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                    raise ValueError("Symlinks, hard links and special files are not allowed")
                if info.st_size > 2_000_000 or len(result) >= 500:
                    raise ValueError("Fixture size limit")
                descriptor = os.open(child.path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                with os.fdopen(descriptor, "rb") as stream:
                    opened = os.fstat(stream.fileno())
                    if (opened.st_ino, opened.st_dev) != (info.st_ino, info.st_dev) or not stat.S_ISREG(opened.st_mode):
                        raise ValueError("Candidate changed during snapshot")
                    data = stream.read(2_000_001)
                total += len(data)
                if len(data) > 2_000_000 or total > 16_000_000:
                    raise ValueError("Fixture byte limit")
                result[str(relative)] = hashlib.sha256(data).hexdigest()
    return dict(sorted(result.items()))


def prepare(task, destination):
    spec = task_spec(task)
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=False)
    copy_overlay(ROOT / "fixtures" / "common", destination)
    copy_overlay(ROOT / "fixtures" / task, destination)
    for name in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                 "gradle/wrapper/gradle-wrapper.properties"):
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(REPO / name, target)
    (destination / "TASK.md").write_text(spec["goal"] + "\n\nTASK-CONVENTIONS.md의 공통 규칙을 따르세요.\n")
    env = {key: os.environ[key] for key in ("PATH", "SYSTEMROOT") if key in os.environ}
    env.update({"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull,
           "GIT_AUTHOR_NAME": "OML benchmark fixture", "GIT_COMMITTER_NAME": "OML benchmark fixture",
           "GIT_AUTHOR_EMAIL": "fixture@example.invalid", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
           "GIT_AUTHOR_DATE": "2000-01-01T00:00:00Z", "GIT_COMMITTER_DATE": "2000-01-01T00:00:00Z"})
    for args in (("-c", "init.templateDir=", "init", "-b", "fixture"), ("add", "."),
                 ("-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-m", task)):
        subprocess.run(["git", *args], cwd=destination, env=env, check=True, capture_output=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=destination, env=env, text=True).strip()
    return {"task": task, "startCommit": commit, "files": snapshot(destination), "spec": spec}


def apply_reference(task, destination):
    """Only coordinator tests may call this. Never exposed as a real agent tool."""
    task_spec(task)
    copy_overlay(ROOT / "references" / task, Path(destination))
