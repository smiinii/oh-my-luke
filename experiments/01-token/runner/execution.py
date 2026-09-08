"""OS-enforced local filesystem separation for offline runner validation."""
import json
from functools import lru_cache
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time


@lru_cache(maxsize=1)
def coordinator_hidden():
    repository = Path(__file__).resolve().parents[3]
    paths = [repository]
    git_file = repository / ".git"
    if git_file.is_file():
        value = git_file.read_text().strip()
        if value.startswith("gitdir: "):
            git_dir = (repository / value[8:]).resolve()
            paths.append(git_dir)
            if git_dir.parent.name == "worktrees":
                paths.append(git_dir.parent.parent.parent)
    for name in (".codex", ".ssh", ".aws", ".config/gh", ".npmrc", ".netrc"):
        path = Path.home() / name
        if path.exists():
            paths.append(path.resolve())
    return tuple(paths)


def sandbox_command(argv, writable, hidden):
    writable = Path(writable).resolve(strict=True)
    hidden = [Path(p).resolve(strict=True) for p in [*hidden, *coordinator_hidden()]]
    if any(p == writable or p.is_relative_to(writable) for p in hidden):
        raise ValueError("Hidden path must not be inside writable space")
    # Avoid mounting a second hidden child over an already hidden parent.
    hidden = [p for p in set(hidden) if not any(p != q and p.is_relative_to(q) for q in hidden)]
    if sys.platform == "darwin":
        # Keep external reads/network available; deny the supplied peer/judge roots.
        filters = " ".join(f"(subpath {json.dumps(str(p))})" for p in hidden)
        exception = f"(require-not (subpath {json.dumps(str(writable))}))"
        read_rule = f"(deny file-read* (require-all (require-any {filters}) {exception}))" if hidden else ""
        profile = f'''(version 1)
(allow default)
{read_rule}
(deny file-write* (require-all {exception} (require-not (literal "/dev/null"))))
'''
        return ["/usr/bin/sandbox-exec", "-p", profile, *argv]
    if sys.platform.startswith("linux"):
        bwrap = shutil.which("bwrap")
        if not bwrap:
            raise RuntimeError("bubblewrap is required; no unsandboxed fallback")
        # Overlay the parent first, then expose only this run; hide peers and judge.
        command = [bwrap, "--die-with-parent", "--new-session", "--unshare-pid",
                   "--unshare-ipc", "--unshare-uts", "--ro-bind", "/", "/",
                   "--proc", "/proc", "--dev", "/dev"]
        for p in sorted(set(hidden), key=lambda p: len(p.parts)):
            command += ["--tmpfs", str(p)] if p.is_dir() else ["--ro-bind", "/dev/null", str(p)]
        command += ["--bind", str(writable), str(writable), "--chdir", str(writable), "--", *argv]
        return command
    raise RuntimeError("Unsupported isolation platform; no unsandboxed fallback")


def isolated_env(writable):
    writable = Path(writable)
    for name in ("home", "tmp"):
        (writable / name).mkdir(exist_ok=True)
    # Do not copy tokens, proxy credentials, user hooks or model configuration.
    env = {key: os.environ[key] for key in ("PATH", "JAVA_HOME", "LANG") if key in os.environ}
    env.update(HOME=str(writable / "home"), TMPDIR=str(writable / "tmp"),
               GRADLE_USER_HOME=str(writable / "home" / ".gradle"),
               GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
               PYTHONDONTWRITEBYTECODE="1", LC_ALL="C", TZ="UTC")
    return env


def execute(argv, writable, hidden, timeout=1200, env=None, output_limit=4_000_000):
    if not argv or timeout <= 0 or timeout > 1200:
        raise ValueError("Invalid command or timeout")
    command = sandbox_command(argv, writable, hidden)
    started = time.monotonic()
    status = "EXITED"
    # Pipes can deadlock on inherited child descriptors. Temporary files plus a
    # process-group cleanup avoid waiting for EOF after the leader has exited.
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        process = subprocess.Popen(command, cwd=writable, env=env or isolated_env(writable),
                                   stdout=out, stderr=err, start_new_session=True)
        try:
            while process.poll() is None:
                if time.monotonic() - started >= timeout:
                    status = "TIMED_OUT"
                    break
                if os.fstat(out.fileno()).st_size + os.fstat(err.fileno()).st_size > output_limit:
                    status = "OUTPUT_LIMIT"
                    break
                time.sleep(0.01)
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
        out.seek(0)
        err.seek(0)
        stdout, stderr = out.read(output_limit), err.read(output_limit)
        if os.fstat(out.fileno()).st_size + os.fstat(err.fileno()).st_size > output_limit:
            status = "OUTPUT_LIMIT"
    if process.returncode != 0 and stderr.startswith((b"sandbox-exec:", b"bwrap:")):
        status = "ENVIRONMENT_ERROR"
    return {"status": status, "exitCode": process.returncode,
            "elapsedMillis": round((time.monotonic() - started) * 1000),
            "stdout": stdout.decode(errors="replace"), "stderr": stderr.decode(errors="replace")}
