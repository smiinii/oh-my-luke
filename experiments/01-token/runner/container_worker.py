"""Disposable, offline preparation workers. NOT a live-AI networking adapter.

Only an explicit project copy crosses the boundary; never mount host paths.
The coordinator freezes every container process before reading candidate files.
"""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import time
import uuid

from fixtures import snapshot


def docker(*args, timeout=30, input=None):
    result = subprocess.run(["docker", *args], capture_output=True, timeout=timeout, input=input)
    if result.returncode:
        raise RuntimeError("Docker operation failed: " + result.stderr.decode(errors="replace")[:2000])
    return result.stdout


def image_identity(image):
    info = json.loads(docker("image", "inspect", image))[0]
    if info.get("Os") != "linux" or not re.fullmatch(r"sha256:[a-f0-9]{64}", info["Id"]):
        raise ValueError("An existing Linux image with an immutable ID is required")
    return {"imageId": info["Id"], "architecture": info["Architecture"],
            "policy": "offline-container-v1", "network": "none", "memoryBytes": 1073741824,
            "cpus": 2, "pids": 64, "workBytes": 268435456, "tmpBytes": 67108864}


def capture(command, timeout, limit):
    start = time.monotonic()
    status = "EXITED"
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        process = subprocess.Popen(command, stdout=out, stderr=err, stdin=subprocess.DEVNULL)
        try:
            while process.poll() is None:
                if time.monotonic() - start >= timeout:
                    status = "TIMED_OUT"
                    break
                if os.fstat(out.fileno()).st_size + os.fstat(err.fileno()).st_size > limit:
                    status = "OUTPUT_LIMIT"
                    break
                time.sleep(0.01)
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
        if os.fstat(out.fileno()).st_size + os.fstat(err.fileno()).st_size > limit:
            status = "OUTPUT_LIMIT"
        out.seek(0)
        err.seek(0)
        return status, process.returncode, out.read(limit), err.read(limit)


def candidate_files(blob):
    """Validate an untrusted Docker tar without extractall or following links."""
    files = {}
    total = 0
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:") as archive:
        for index, member in enumerate(archive):
            path = PurePosixPath(member.name)
            if index >= 2000 or path.is_absolute() or ".." in path.parts or len(path.parts) > 33:
                raise ValueError("Invalid or oversized candidate archive")
            if not path.parts or path.parts[0] != "workspace":
                raise ValueError("Unexpected candidate archive root")
            if not (member.isdir() or member.isfile()):
                raise ValueError("Candidate links and special files are forbidden")
            if len(path.parts) > 1 and path.parts[1] in (".git", ".gradle", "build"):
                continue
            if member.isdir():
                continue
            name = str(path.relative_to("workspace"))
            total += member.size
            if name == "." or name in files or len(files) >= 500 or member.size > 2_000_000 or total > 16_000_000:
                raise ValueError("Duplicate or oversized candidate files")
            files[name] = archive.extractfile(member).read()
    if not files:
        raise ValueError("Empty candidate archive")
    return files


def execute_container(argv, writable, image, timeout=10, output_limit=4_000_000, task="pilot"):
    if not argv or not 0 < timeout <= 1200 or type(output_limit) is not int or not 0 < output_limit <= 4_000_000:
        raise ValueError("Invalid execution limits")
    writable = Path(writable).resolve(strict=True)
    workspace = writable / "workspace"
    if task not in ("a", "b", "c", "pilot"):
        raise ValueError("Unknown fixture task")
    baseline = snapshot(workspace)
    policy = image_identity(image)
    name = "oml-prep-" + uuid.uuid4().hex
    cid = exporter = volume = None
    started = time.monotonic()
    outcome = {"status": "ENVIRONMENT_ERROR", "exitCode": None, "stdout": "", "stderr": "",
               "isolation": policy}
    try:
        volume = docker("volume", "create", "--label", "io.ohmyluke.preparation=true",
                        "--driver", "local", "--opt", "type=tmpfs", "--opt", "device=tmpfs",
                        "--opt", "o=size=256m,uid=1000,gid=1000,mode=700", name).decode().strip()
        cid = docker("create", "--name", name, "--label", "io.ohmyluke.preparation=true",
                     "--network", "none", "--read-only", "--cap-drop", "ALL",
                     "--security-opt", "no-new-privileges", "--memory", "1g", "--memory-swap", "1g",
                     "--cpus", "2", "--pids-limit", "64", "--ulimit", "nofile=256:256",
                     "--ulimit", "core=0:0", "--log-driver", "none", "--user", "1000:1000",
                     "--workdir", "/work", "--mount", f"type=volume,src={volume},dst=/work,volume-nocopy",
                     "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777",
                     "-e", "HTTP_PROXY=", "-e", "HTTPS_PROXY=", "-e", "ALL_PROXY=", "-e", "NO_PROXY=",
                     "-e", "http_proxy=", "-e", "https_proxy=", "-e", "all_proxy=", "-e", "no_proxy=",
                     "--entrypoint", "/bin/sleep", policy["imageId"], "infinity").decode().strip()
        # A private regular-file-only seed; no .git remote, hooks, caches or host config.
        seed = io.BytesIO()
        with tarfile.open(fileobj=seed, mode="w") as archive:
            for relative, expected in baseline.items():
                content = (workspace / relative).read_bytes()
                if hashlib.sha256(content).hexdigest() != expected:
                    raise ValueError("Input changed during snapshot")
                entry = tarfile.TarInfo("workspace/" + relative)
                entry.size = len(content)
                entry.mode = 0o755 if relative == "gradlew" else 0o644
                archive.addfile(entry, io.BytesIO(content))
        docker("start", cid)
        docker("exec", "-i", cid, "tar", "-xf", "-", "--no-same-owner", "-C", "/work", input=seed.getvalue())
        docker("exec", cid, "mkdir", "/work/home", "/work/tmp")
        git_env = {"GIT_AUTHOR_NAME": "OML benchmark fixture", "GIT_COMMITTER_NAME": "OML benchmark fixture",
                   "GIT_AUTHOR_EMAIL": "fixture@example.invalid", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
                   "GIT_AUTHOR_DATE": "2000-01-01T00:00:00Z", "GIT_COMMITTER_DATE": "2000-01-01T00:00:00Z"}
        env_args = [arg for key, value in git_env.items() for arg in ("-e", key + "=" + value)]
        for args in [("-c", "init.templateDir=", "init", "-b", "fixture"), ("add", "."),
                     ("-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-m", task)]:
            docker("exec", *env_args, "-w", "/work/workspace", cid, "git", *args)
        outcome["isolation"]["workerStartCommit"] = docker("exec", "-w", "/work/workspace", cid,
                                                           "git", "rev-parse", "HEAD").decode().strip()
        # Docker, not the worker's own stdout, supplies the exec exit status.
        status, code, out, err = capture(["docker", "exec", cid, *argv], timeout, output_limit)
        if code in (126, 127) and (b"OCI runtime exec failed" in out + err):
            status = "ENVIRONMENT_ERROR"
        outcome.update(status=status, exitCode=code, stdout=out.decode(errors="replace"), stderr=err.decode(errors="replace"))
        # Includes detached/session-changing descendants: no writable live tree is exported.
        docker("pause", cid)
        state = json.loads(docker("inspect", cid))[0]
        mounts = state["Mounts"]
        if (not state["State"]["Paused"] or state["HostConfig"]["NetworkMode"] != "none"
                or len(mounts) != 1 or mounts[0]["Type"] != "volume" or mounts[0]["Name"] != volume):
            raise RuntimeError("Container isolation state did not match policy")
        outcome["isolation"]["frozenBeforeExport"] = True
        if status == "EXITED" and code == 0:
            # Docker cp does not reliably expose tmpfs contents. A trusted, read-only
            # exporter sees this one frozen volume, never executing candidate code.
            exporter = docker("create", "--network", "none", "--read-only", "--cap-drop", "ALL",
                              "--security-opt", "no-new-privileges", "--memory", "256m", "--memory-swap", "256m",
                              "--cpus", "1", "--pids-limit", "16", "--log-driver", "none",
                              "--mount", f"type=volume,src={volume},dst=/capture,readonly,volume-nocopy",
                              "--entrypoint", "/bin/tar", policy["imageId"],
                              "-cf", "-", "-C", "/capture", "workspace").decode().strip()
            transfer, exit_code, blob, error = capture(["docker", "start", "-a", exporter], 30, 64_000_000)
            if transfer != "EXITED" or exit_code != 0:
                raise ValueError("Candidate export failed or exceeded its limit: " + error.decode(errors="replace")[:200])
            files = candidate_files(blob)
            # Only coordinator-owned pre-existing regular paths can be replaced.
            if snapshot(workspace) != baseline:
                raise ValueError("Coordinator workspace changed during execution")
            for relative in baseline:
                if relative not in files:
                    (workspace / relative).unlink()
            for relative, content in files.items():
                target = workspace / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, tarfile.TarError) as error:
        outcome.update(status="ENVIRONMENT_ERROR", stderr=str(error)[:2000])
    finally:
        cleanup_errors = []
        operations = [("rm", "--force", item) for item in (exporter, cid) if item]
        if volume:
            operations.append(("volume", "rm", volume))
        for operation in operations:
            try:
                docker(*operation)
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                cleanup_errors.append(str(error)[:2000])
        outcome["isolation"]["removed"] = not cleanup_errors
        if cleanup_errors:
            outcome["isolation"]["cleanupErrors"] = cleanup_errors
            outcome.update(status="ENVIRONMENT_ERROR", stderr="Cleanup incomplete; stop experiment admission")
    outcome["elapsedMillis"] = round((time.monotonic() - started) * 1000)
    return outcome
