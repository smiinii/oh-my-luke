#!/usr/bin/env python3
"""Offline experiment preparation. No entry point launches a real AI CLI."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

from evaluator import evaluate, java_home
from execution import execute
from fixtures import ROOT, prepare, task_spec
from report import build_report, protocol_hash, schedule
from usage import summarize
from readiness import live_readiness
from container_worker import execute_container, image_identity
from container_worker import docker
from recovery import DEFAULT_ROOT, recover_abandoned


def write_json(path, value):
    with Path(path).open("x") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def preflight():
    tools = {}
    for name in ("codex", "omx", "omluke"):
        executable = shutil.which(name)
        tools[name] = {"found": executable is not None, "path": executable}
        # --version only: do not load credentials or start work.
        if executable:
            try:
                probe = subprocess.run([executable, "--version"], capture_output=True, text=True, timeout=10)
                tools[name]["version"] = probe.stdout.strip()[:200]
            except (OSError, subprocess.TimeoutExpired):
                tools[name]["version"] = None
    return {"synthetic": True, "actualAiCalls": 0, "tools": tools, **live_readiness(),
            "platform": sys.platform, "javaHome": str(java_home())}


def dry_run(output, task="pilot", mode="success", container_image=None):
    task_spec(task)
    isolation = image_identity(container_image) if container_image else {"policy": "legacy-local-regression", "network": "none"}
    if container_image:
        container_image = isolation["imageId"]  # Freeze once, never re-resolve a mutable tag per arm.
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    runs = schedule((task,), 1)
    baselines = {}
    for item in runs:
        worker = output / item["runId"] / "worker"
        worker.mkdir(parents=True)
        baseline = prepare(task, worker / "workspace")
        item["startCommit"] = baseline["startCommit"]
        baselines[item["runId"]] = baseline
    protocol = {"version": 1, "tasks": [task], "repetitions": 1, "timeoutSeconds": 1200,
                "executor": "synthetic-python-worker", "network": "none", "isolation": isolation, "model": "NONE",
                "startCommits": {task: runs[0]["startCommit"]},
                "toolHashes": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for folder in ("runner", "evaluator", "tasks", "references")
                               for p in sorted((ROOT / folder).rglob("*")) if p.is_file() and p.suffix in (".py", ".java", ".json")}}
    plan = {"synthetic": True, "protocol": protocol, "protocolHash": protocol_hash(protocol), "runs": runs}
    records = []
    write_json(output / "manifest.json", plan)
    for item in runs:
        run_root = output / item["runId"]
        worker = run_root / "worker"
        workspace = worker / "workspace"
        baseline = baselines[item["runId"]]
        write_json(run_root / "baseline.json", baseline)
        patches = {str(p.relative_to(ROOT / "references" / task)): p.read_text()
                   for p in (ROOT / "references" / task).rglob("*.java")} if mode == "success" else {}
        events = [{"type": "thread.started", "thread_id": item["runId"]}, {"type": "turn.started"},
                  {"type": "turn.completed", "usage": {"input_tokens": 100, "output_tokens": 20,
                   "cached_input_tokens": 50, "reasoning_output_tokens": 5}}]
        code = "import json,pathlib,time,sys\n"
        code += f"patches=json.loads({json.dumps(json.dumps(patches))})\n"
        code += "for name,content in patches.items():\n p=pathlib.Path('workspace')/name\n p.parent.mkdir(parents=True,exist_ok=True)\n p.write_text(content)\n"
        code += "print(" + repr("\n".join(json.dumps(e) for e in events)) + ",flush=True)\n"
        if mode == "timeout":
            code += "time.sleep(60)\n"
        command = ["python3" if container_image else sys.executable, "-c", code] if mode != "environment_error" else ["/nonexistent/oml-fixture"]
        try:
            timeout = 0.1 if mode == "timeout" else 10
            outcome = (execute_container(command, worker, container_image, timeout=timeout, task=task)
                       if container_image else execute(command, worker, [output, ROOT], timeout=timeout, network=False))
            if container_image and outcome.get("isolation", {}).get("workerStartCommit") != baseline["startCommit"]:
                outcome.update(status="ENVIRONMENT_ERROR", stderr="Container start commit does not match frozen fixture")
        except OSError as error:
            outcome = {"status": "ENVIRONMENT_ERROR", "exitCode": None, "stdout": "", "stderr": str(error), "elapsedMillis": 0}
        (run_root / "stdout.jsonl").write_text(outcome["stdout"])
        (run_root / "stderr.txt").write_text(outcome["stderr"])
        write_json(run_root / "execution.json", outcome)
        usage_manifest = {"synthetic": True, "inventoryComplete": mode == "success",
                          "sessions": [{"id": item["runId"], "parentId": None, "scope": "self",
                          "schema": "codex-exec-jsonl-v1", "log": "stdout.jsonl",
                          "sha256": hashlib.sha256(outcome["stdout"].encode()).hexdigest()}]}
        write_json(run_root / "usage-manifest.json", usage_manifest)
        usage = summarize(usage_manifest, run_root)
        # A failed process never becomes successful merely because files pass.
        judge = evaluate(task, workspace, baseline) if outcome["status"] == "EXITED" and outcome["exitCode"] == 0 else None
        status = judge["status"] if judge else ("FAILED" if outcome["status"] == "EXITED" else outcome["status"])
        record = {**item, "synthetic": True, "protocolHash": plan["protocolHash"],
                  "startCommit": baseline["startCommit"], "status": status, "usage": usage,
                  "elapsedMillis": outcome["elapsedMillis"], "judgeMillis": judge["judgeMillis"] if judge else 0,
                  "cliExecutions": 1, "actualAiCalls": 0, "reason": judge["reason"] if judge else outcome["status"]}
        write_json(run_root / "result.json", record)
        records.append(record)
        if outcome.get("isolation", {}).get("removed") is False:
            break  # Keep the failed attempt, but never start another worker after failed cleanup.
    write_json(output / "results.json", records)
    report = build_report(plan, records)
    (output / "report.md").write_text(report["markdown"])
    (output / "summary.csv").write_text(report["csv"])
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("preflight")
    recovery = commands.add_parser("recover", help="Clean abandoned owned workers; never silently resume an interrupted attempt")
    recovery.add_argument("--registry", type=Path, default=DEFAULT_ROOT)
    dry = commands.add_parser("dry-run")
    dry.add_argument("output", type=Path)
    dry.add_argument("--task", choices=("a", "b", "c", "pilot"), default="pilot")
    dry.add_argument("--mode", choices=("success", "fail", "timeout", "environment_error"), default="success")
    dry.add_argument("--container-image", help="Explicit prebuilt offline image; no fallback when Docker fails")
    report = commands.add_parser("report")
    report.add_argument("directory", type=Path)
    args = parser.parse_args()
    if args.command == "preflight":
        print(json.dumps(preflight(), indent=2))
    elif args.command == "recover":
        print(json.dumps(recover_abandoned(docker, args.registry), indent=2))
    elif args.command == "dry-run":
        print(json.dumps(dry_run(args.output, args.task, args.mode, args.container_image), indent=2))
    else:
        directory = args.directory
        content = build_report(json.loads((directory / "manifest.json").read_text()),
                               json.loads((directory / "results.json").read_text()))
        print(content["markdown"], end="")


if __name__ == "__main__":
    main()
