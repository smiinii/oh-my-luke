"""Paired summaries reject missing runs, duplicate IDs and mixed protocols."""
import csv
import hashlib
import io
import json
from statistics import median

ARMS = ("codex", "omx", "oml")


def schedule(tasks=("a", "b", "c"), repetitions=3):
    if repetitions < 1 or len(set(tasks)) != len(tasks):
        raise ValueError("Invalid schedule")
    runs = []
    for task_index, task in enumerate(tasks):
        for repeat in range(repetitions):
            shift = (repeat + task_index) % len(ARMS)
            for arm in ARMS[shift:] + ARMS[:shift]:
                runs.append({"runId": f"{task}-{repeat+1}-{arm}", "task": task,
                             "repeat": repeat+1, "arm": arm})
    return runs


def protocol_hash(protocol):
    return hashlib.sha256(json.dumps(protocol, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def build_report(plan, records):
    if "protocol" in plan and protocol_hash(plan["protocol"]) != plan["protocolHash"]:
        raise ValueError("Protocol hash mismatch")
    expected = {r["runId"]: r for r in plan["runs"]}
    if len(expected) != len(plan["runs"]):
        raise ValueError("Duplicate planned run")
    actual = {}
    for record in records:
        identity = record["runId"]
        if identity not in expected or identity in actual:
            raise ValueError("Unexpected or duplicate run")
        if record["protocolHash"] != plan["protocolHash"]:
            raise ValueError("Mixed experiment protocols")
        if record["synthetic"] != plan["synthetic"]:
            raise ValueError("Mixed synthetic and real results")
        expected_commit = expected[identity].get("startCommit")
        if expected_commit is not None and record.get("startCommit") != expected_commit:
            raise ValueError("Different start commit")
        if any(record[k] != expected[identity][k] for k in ("task", "repeat", "arm")):
            raise ValueError("Run identity mismatch")
        total = record["usage"]["totalTokens"]
        if total is not None and (type(total) is not int or total < 0):
            raise ValueError("Invalid token total")
        if record["usage"]["allTokenUsageAvailable"] != (total is not None):
            raise ValueError("Inconsistent usage completeness")
        actual[identity] = record
    rows = []
    lines = ["# SYNTHETIC — 준비 도구 검증 결과" if plan["synthetic"] else "# 개발 작업 비교 결과",
             "", "실제 모델 성능 결과가 아닙니다." if plan["synthetic"] else "실험 범위 내에서 해석하세요.", "",
             "| 과제 | 도구 | 성공/계획 | 미실행 | 누락 토큰 | 전체 토큰 중앙값 [범위] | 성공 토큰 중앙값 | 시간 중앙값(ms) | 전체 소비/성공 |",
             "|---|---|---:|---:|---:|---|---:|---:|---:|"]
    for task in dict.fromkeys(r["task"] for r in plan["runs"]):
        for arm in ARMS:
            planned = [r for r in plan["runs"] if r["task"] == task and r["arm"] == arm]
            completed = [actual[r["runId"]] for r in planned if r["runId"] in actual]
            successes = [r for r in completed if r["status"] == "SUCCEEDED"]
            known_successes = [r["usage"]["totalTokens"] for r in successes if r["usage"]["allTokenUsageAvailable"]]
            missing = len(planned) - len(completed)
            unavailable = sum(not r["usage"]["allTokenUsageAvailable"] for r in completed)
            per_success = None if missing or unavailable or not successes else sum(r["usage"]["totalTokens"] for r in completed) / len(successes)
            middle = median(known_successes) if known_successes else None
            known_all = [r["usage"]["totalTokens"] for r in completed if r["usage"]["allTokenUsageAvailable"]]
            all_summary = f"{median(known_all)} [{min(known_all)}, {max(known_all)}]" if known_all else "None"
            elapsed = median(r["elapsedMillis"] for r in completed) if completed else None
            lines.append(f"| {task} | {arm} | {len(successes)}/{len(planned)} | {missing} | {unavailable} | {all_summary} | {middle} | {elapsed} | {per_success} |")
    lines += ["", "## 같은 과제·반복의 성공 쌍", "", "| 기준 | 비교 가능한 쌍 | 쌍별 절감률 중앙값 |", "|---|---:|---:|"]
    for arm in ("codex", "omx"):
        savings = []
        for r in actual.values():
            if r["arm"] != "oml" or r["status"] != "SUCCEEDED":
                continue
            baseline = actual.get(f'{r["task"]}-{r["repeat"]}-{arm}')
            if not baseline or baseline["status"] != "SUCCEEDED":
                continue
            left, right = baseline["usage"]["totalTokens"], r["usage"]["totalTokens"]
            if left is not None and left > 0 and right is not None:
                savings.append((left - right) / left * 100)
        lines.append(f"| {arm} | {len(savings)} | {median(savings) if savings else None} |")
    fields = ["synthetic", "protocolHash", "runId", "task", "repeat", "arm", "startCommit", "status", "totalTokens", "usageComplete", "elapsedMillis", "judgeMillis", "cliExecutions"]
    for item in plan["runs"]:
        r = actual.get(item["runId"])
        rows.append({**item, "synthetic": plan["synthetic"], "protocolHash": plan["protocolHash"],
                     "status": r["status"] if r else "NOT_RUN",
                     "totalTokens": r["usage"]["totalTokens"] if r else None,
                     "usageComplete": r["usage"]["allTokenUsageAvailable"] if r else False,
                     "elapsedMillis": r["elapsedMillis"] if r else None,
                     "judgeMillis": r.get("judgeMillis") if r else None,
                     "cliExecutions": r.get("cliExecutions") if r else None})
    csv_buffer = io.StringIO(newline="")
    writer = csv.DictWriter(csv_buffer, fieldnames=fields)
    writer.writeheader()
    writer.writerows(rows)
    return {"markdown": "\n".join(lines) + "\n", "csv": csv_buffer.getvalue()}
