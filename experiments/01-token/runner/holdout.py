"""Seal coordinator-authored private tasks outside Git and verify before use.

Layout per task: worker/, reference/ (overlay), judge/Probe.java,
judge/expected.json, spec.json; optional mutant/ overlay for regression tests.
No private task or answer is a worker-image build input or a public artifact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time

from evaluator import java_home
from execution import execute
from fixtures import ROOT, REPO, copy_overlay, snapshot


def hashes(root):
    if any((Path(root) / name).exists() or (Path(root) / name).is_symlink() for name in (".git", ".gradle", "build")):
        raise ValueError("Private material must not contain unhashed runtime directories")
    return snapshot(root)


def outside_git(path):
    path = Path(path).resolve()
    if path.is_relative_to(REPO):
        raise ValueError("Private material must stay outside the public repository")
    for parent in (path, *path.parents):
        if (parent / ".git").exists():
            raise ValueError("Private material must not be inside any Git checkout")
    return path


def seal(source, destination):
    source = outside_git(source)
    destination = outside_git(destination)
    if source == destination or destination.is_relative_to(source):
        raise ValueError("Seal destination must be independent")
    before = hashes(source)
    if not before or "manifest.json" in before:
        raise ValueError("Expected unsealed private sources")
    tasks = sorted(p.name for p in source.iterdir() if p.is_dir())
    if tasks != ["a", "b", "c"]:
        raise ValueError("Expected private A/B/C task set")
    for task in tasks:
        for required in ("spec.json", "judge/Probe.java", "judge/expected.json", "worker/TASK.md"):
            if f"{task}/{required}" not in before:
                raise ValueError("Missing private task material: " + task + "/" + required)
    destination.mkdir(parents=True, exist_ok=False, mode=0o700)
    copy_overlay(source, destination)
    if hashes(source) != before or hashes(destination) != before:
        raise ValueError("Private sources changed during freeze")
    material = json.dumps(before, sort_keys=True, separators=(",", ":")).encode()
    manifest = {"schema": 1, "private": True, "tasks": tasks, "files": before,
                "bundleHash": hashlib.sha256(material).hexdigest()}
    with (destination / "manifest.json").open("x") as stream:
        json.dump(manifest, stream, indent=2)
    return manifest


def scaffold(source):
    source = outside_git(source)
    if (source / "manifest.json").exists():
        raise ValueError("Do not modify sealed material")
    for task in ("a", "b", "c"):
        worker = source / task / "worker"
        for name in ("build.gradle.kts", "settings.gradle.kts"):
            destination = worker / name
            if destination.exists():
                raise FileExistsError(destination)
            shutil.copy2(ROOT / "fixtures/common" / name, destination)
        for name in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
            destination = worker / name
            if destination.exists():
                raise FileExistsError(destination)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(REPO / name, destination)
    # A regression must detect the original boundary bug, not merely contain assertions.
    mutant = source / "a/mutant"
    mutant.mkdir(exist_ok=False)
    copy_overlay(source / "a/worker/src/main/java/benchmark/order", mutant)
    os.chmod(source, 0o700)


def verify(bundle, expected_hash=None):
    bundle = outside_git(bundle)
    manifest = json.loads((bundle / "manifest.json").read_text())
    current = hashes(bundle)
    current.pop("manifest.json", None)
    digest = hashlib.sha256(json.dumps(current, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if current != manifest["files"] or digest != manifest["bundleHash"] or (expected_hash is not None and digest != expected_hash):
        raise ValueError("Frozen private material changed")
    return manifest


def prepare(bundle, task, destination):
    verify(bundle)
    if task not in ("a", "b", "c"):
        raise ValueError("Unknown private task")
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    copy_overlay(Path(bundle) / task / "worker", destination)
    return hashes(destination)


def evaluate(bundle, task, candidate):
    verify(bundle)
    material = Path(bundle) / task
    spec = json.loads((material / "spec.json").read_text())
    original, current = hashes(material / "worker"), snapshot(candidate)
    for name, digest in original.items():
        if name not in current or (not name.startswith("src/main/") and current[name] != digest):
            return "forbidden-change"
    added = sorted(set(current) - set(original))
    if any(not re.fullmatch(r"src/(?:main|test)/java/benchmark/order/[A-Za-z][A-Za-z0-9]*\.java", name) for name in added):
        return "forbidden-change"
    tests = [name for name in added if name.startswith("src/test/") and name.endswith("Checks.java")]
    if spec["requiresAddedTests"] and not tests:
        return "missing-regression-test"
    jdk = java_home()
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="oml-private-judge-") as temp:
        root = Path(temp).resolve()
        stage, classes = root / "stage", root / "stage/classes"
        classes.mkdir(parents=True)
        copy_overlay(Path(candidate) / "src", stage / "src")
        frozen = {"src/" + name: digest for name, digest in hashes(stage / "src").items()}
        if snapshot(candidate) != current or frozen != {name: digest for name, digest in current.items() if name.startswith("src/")}:
            return "candidate-changed"
        shutil.copy2(material / "judge/Probe.java", stage / "Probe.java")

        def run(tool, args, compile=False):
            remaining = 120 - (time.monotonic() - started)
            if remaining <= 0:
                raise RuntimeError("Private judge deadline exceeded")
            flags = ["-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-XX:-UsePerfData"]
            if tool != "java":
                flags = ["-J" + flag for flag in flags]
            scratch = stage if compile else Path(tempfile.mkdtemp(dir=root, prefix="scratch-"))
            result = execute([str(jdk / ("bin/" + tool)), *flags, *args], scratch, [Path(bundle), Path(candidate)],
                             timeout=min(30, remaining), readable=[jdk, stage if compile else classes], network=False)
            if result["status"] != "EXITED":
                raise RuntimeError("Private evaluator environment/limit failure")
            return result

        sources = [str(p) for p in sorted((stage / "src").rglob("*.java"))]
        if run("javac", ["--release", "21", "-proc:none", "-d", str(classes), *sources, str(stage / "Probe.java")], True)["exitCode"]:
            return "compile-failed"
        java = ["-ea", "-cp", str(classes)]
        for test in (stage / "src/test/java/benchmark/order").glob("*Checks.java"):
            if run("java", [*java, "benchmark.order." + test.stem])["exitCode"]:
                return "test-failed"
        observations = run("java", [*java, "benchmark.order.Probe"])
        expected = json.loads((material / "judge/expected.json").read_text())
        if observations["exitCode"] or observations["stdout"].splitlines() != expected:
            return "requirements-failed"
        for service, invocation in spec.get("requiredCalls", {}).items():
            output = run("javap", ["-c", "-p", "-classpath", str(classes), "benchmark.order." + service])
            if output["exitCode"] or invocation not in output["stdout"] or re.search(r"\d+:\s+(?:if\w*|[a-z]*switch)\b", output["stdout"]):
                return "structure-failed"
        if spec["requiresAddedTests"]:
            copy_overlay(material / "mutant", stage / "src/main/java/benchmark/order")
            mutant = [str(p) for p in sorted((stage / "src/main").rglob("*.java"))]
            if run("javac", ["--release", "21", "-proc:none", "-d", str(classes), *mutant], True)["exitCode"]:
                raise RuntimeError("Private mutant compilation failed")
            if not any(run("java", [*java, "benchmark.order." + Path(name).stem])["exitCode"] for name in tests):
                return "vacuous-regression-test"
        return "SUCCEEDED"


def check_references(bundle, expected_hash=None):
    manifest = verify(bundle, expected_hash)
    results = {}
    for task in manifest["tasks"]:
        with tempfile.TemporaryDirectory(prefix="oml-holdout-check-") as temp:
            candidate = Path(temp) / "candidate"
            prepare(bundle, task, candidate)
            # Exercise the actual original defect/API with the new reference tests,
            # not just reject the absence of newly added regression files.
            copy_overlay(Path(bundle) / task / "reference/src/test", candidate / "src/test")
            before = evaluate(bundle, task, candidate)
            copy_overlay(Path(bundle) / task / "reference", candidate)
            after = evaluate(bundle, task, candidate)
            if before == "SUCCEEDED" or after != "SUCCEEDED":
                raise ValueError("Private task readiness failed: " + task + ": " + before + " / " + after)
            results[task] = {"original": before, "reference": after}
    return {"bundleHash": manifest["bundleHash"], "actualAiCalls": 0, "checks": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    freeze = sub.add_parser("seal")
    freeze.add_argument("source", type=Path)
    freeze.add_argument("destination", type=Path)
    check = sub.add_parser("check")
    check.add_argument("bundle", type=Path)
    check.add_argument("--expected-hash", required=True, help="Previously frozen digest kept separately from the bundle")
    support = sub.add_parser("scaffold")
    support.add_argument("source", type=Path)
    args = parser.parse_args()
    if args.command == "scaffold":
        scaffold(args.source)
        raise SystemExit(0)
    result = seal(args.source, args.destination) if args.command == "seal" else check_references(args.bundle, args.expected_hash)
    # Do not print private file hashes, source code, expected values or prompts.
    print(json.dumps({key: value for key, value in result.items() if key in ("bundleHash", "actualAiCalls", "checks")}, indent=2))
