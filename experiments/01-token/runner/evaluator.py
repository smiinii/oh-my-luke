"""Evaluate a frozen source copy, never candidate Gradle files or scripts."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import uuid

from execution import execute
from fixtures import ROOT, copy_overlay, snapshot, task_spec
from oracle import expected_observations


def java_home():
    value = os.environ.get("OML_BENCH_JAVA_HOME") or os.environ.get("JAVA_HOME")
    if not value and Path("/usr/libexec/java_home").is_file():
        value = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    if not value or not (Path(value) / "bin/javac").is_file():
        raise RuntimeError("Set OML_BENCH_JAVA_HOME to JDK 21")
    home = Path(value).resolve()
    version = subprocess.check_output([str(home / "bin/javac"), "-version"], text=True).strip()
    if not version.startswith("javac 21."):
        raise RuntimeError("Benchmark requires JDK 21, got " + version)
    return home


def allowed_changes(task, original, current):
    for name, digest in original.items():
        if name not in current:
            return False
        if not name.startswith("src/main/java/benchmark/order/") and current[name] != digest:
            return False
    return all(re.fullmatch(r"src/(?:main|test)/java/benchmark/order/[A-Za-z][A-Za-z0-9]*\.java", name)
               for name in current.keys() - original.keys())


def evaluate(task, candidate, baseline):
    spec = task_spec(task)
    started = time.monotonic()
    def result(status, reason, evidence=""):
        return {"status": status, "reason": reason, "evidence": evidence[-8000:],
                "judgeMillis": round((time.monotonic() - started) * 1000)}
    try:
        current = snapshot(candidate)
    except (ValueError, OSError) as error:
        return result("FAILED", "invalid-tree", str(error))
    if not allowed_changes(task, baseline["files"], current):
        return result("FAILED", "forbidden-change")
    added = sorted(name for name in current.keys() - baseline["files"].keys()
                   if name.startswith("src/test/") and name.endswith("Checks.java"))
    if spec["requiresAddedTests"] and not added:
        return result("FAILED", "missing-regression-test")
    jdk = java_home()
    with tempfile.TemporaryDirectory(prefix="oml-judge-") as temporary:
        root = Path(temporary).resolve()
        stage = root / "stage"
        stage.mkdir()
        copy_overlay(Path(candidate) / "src", stage / "src")
        # Detect source changes during the freeze rather than judging mixed versions.
        frozen = {"src/" + name: digest for name, digest in snapshot(stage / "src").items()}
        if snapshot(candidate) != current or frozen != {name: digest for name, digest in current.items() if name.startswith("src/")}:
            return result("ENVIRONMENT_ERROR", "candidate-changed-during-freeze")
        shutil.copy2(ROOT / "evaluator/Judge.java", stage / "Judge.java")
        classes = stage / "classes"
        classes.mkdir()
        hidden = [ROOT, Path(candidate)]
        def run(argv, timeout=10, compiler=False):
            # Each candidate process gets fresh scratch; classes and oracle stay immutable.
            remaining = 120 - (time.monotonic() - started)
            if remaining <= 0:
                return {"status": "TIMED_OUT", "exitCode": -1, "stdout": "", "stderr": "evaluation deadline"}
            # Bounds apply only to the small benchmark JVMs, never the user's JDK settings.
            vm = ["-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2",
                  "-XX:+DisableAttachMechanism", "-XX:-UsePerfData"]
            if Path(argv[0]).name in ("javac", "javap"):
                argv = [argv[0], *("-J" + flag for flag in vm), *argv[1:]]
            elif Path(argv[0]).name == "java":
                argv = [argv[0], *vm, *argv[1:]]
            scratch = stage if compiler else stage / ("scratch-" + uuid.uuid4().hex)
            scratch.mkdir(exist_ok=True)
            return execute(argv, scratch, hidden, timeout=min(timeout, remaining),
                           readable=[jdk, stage if compiler else classes], network=False)
        sources = sorted(str(p) for p in (stage / "src").rglob("*.java"))
        compiled = run([str(jdk / "bin/javac"), "--release", "21", "-proc:none", "-d", str(classes),
                        *sources, str(stage / "Judge.java")], timeout=30, compiler=True)
        if compiled["status"] == "ENVIRONMENT_ERROR":
            return result("ENVIRONMENT_ERROR", "compiler-environment", compiled["stderr"])
        if compiled["status"] != "EXITED":
            return result("FAILED", "compile-limit", compiled["stderr"])
        if compiled["exitCode"] != 0:
            return result("FAILED", "compile-failed", compiled["stderr"])
        classpath = [str(jdk / "bin/java"), "-ea", "-cp", str(classes)]
        for file in sorted((stage / "src/test/java/benchmark/order").glob("*Checks.java")):
            checked = run([*classpath, "benchmark.order." + file.stem])
            if checked["status"] == "ENVIRONMENT_ERROR":
                return result("ENVIRONMENT_ERROR", "test-environment", checked["stderr"])
            if checked["status"] != "EXITED" or checked["exitCode"] != 0:
                return result("FAILED", "public-test-failed", checked["stderr"])
        judged = run([*classpath, "benchmark.order.Judge", task])
        if judged["status"] == "ENVIRONMENT_ERROR":
            return result("ENVIRONMENT_ERROR", "judge-environment", judged["stderr"])
        if judged["status"] != "EXITED" or judged["exitCode"] != 0 or expected_observations(task) != judged["stdout"].splitlines():
            return result("FAILED", "requirements-failed", judged["stderr"])
        if task == "c":
            for service in ("CheckoutService", "QuoteService"):
                disassembly = run([str(jdk / "bin/javap"), "-c", "-p", "-classpath", str(classes),
                                   "benchmark.order." + service])
                code = disassembly["stdout"]
                if disassembly["status"] == "ENVIRONMENT_ERROR":
                    return result("ENVIRONMENT_ERROR", "disassembly-environment", disassembly["stderr"])
                if disassembly["exitCode"] != 0 or "OrderLineValidator.validate:" not in code:
                    return result("FAILED", "missing-validator-call")
                if re.search(r"\d+:\s+(?:if\w*|[a-z]*switch)\b", code):
                    return result("FAILED", "duplicate-service-validation")
        # A/B must add tests which kill the original bug or a no-discount mutant.
        if added:
            calculator = stage / "src/main/java/benchmark/order/OrderCalculator.java"
            if task == "a":
                original_source = ROOT / "fixtures/a/src/main/java/benchmark/order/OrderCalculator.java"
                shutil.copy2(original_source, calculator)
            elif task == "b":
                calculator.write_text('''package benchmark.order;
public final class OrderCalculator {
 public long shippingFee(long value) { return value >= 50000 ? 0 : 3000; }
 public long total(long value) { return value + shippingFee(value); }
 public long total(long value, long discount) { return total(value); }
}
''')
            if task in ("a", "b"):
                mutant = run([str(jdk / "bin/javac"), "--release", "21", "-proc:none", "-d", str(classes),
                              str(calculator)], timeout=30, compiler=True)
                if mutant["exitCode"] != 0:
                    return result("ENVIRONMENT_ERROR", "mutant-compile-failed", mutant["stderr"])
                killed = False
                for name in added:
                    checked = run([*classpath, "benchmark.order." + Path(name).stem])
                    if checked["status"] == "ENVIRONMENT_ERROR":
                        return result("ENVIRONMENT_ERROR", "mutation-test-environment", checked["stderr"])
                    killed |= checked["status"] == "EXITED" and checked["exitCode"] != 0
                if not killed:
                    return result("FAILED", "vacuous-regression-test")
        return result("SUCCEEDED", "all-checks-passed")
