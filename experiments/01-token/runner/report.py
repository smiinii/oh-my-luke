"""Paired summaries reject missing runs, duplicate IDs and mixed protocols."""
import csv
import hashlib
import io
import json
from statistics import median
from review import validate, digest

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


def build_report(plan, records, reviews=None):
    reviews = reviews or {}
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
    if set(reviews) - set(actual):
        raise ValueError("Review for unknown attempt")
    for identity, review in reviews.items():
        validate(review, actual[identity])
    def integrity(record):
        return reviews.get(record["runId"], {}).get("decision", "UNREVIEWED")
    def eligible(record):
        return integrity(record) == "CLEAR"
    rows = []
    lines = ["# SYNTHETIC — 준비 도구 검증 결과" if plan["synthetic"] else "# 개발 작업 비교 결과",
             "", "실제 모델 성능 결과가 아닙니다." if plan["synthetic"] else "실험 범위 내에서 해석하세요.", "",
             "CLEAR는 관측 기록 검토에서 위반을 찾지 못했다는 뜻이며 완전한 무오염 보증이 아닙니다.",
             "오염·미검토는 비교에서 제외하고 원시 소비량과 실행 기록은 보존합니다.", "",
             "| 과제 | 도구 | 성공/비교대상 | 계획 | 미실행 | 오염 | 미검토 | 비교 토큰 중앙값 [범위] | 성공 토큰 중앙값 | 비교 시간 중앙값(ms) | 비교 소비/성공 |",
             "|---|---|---:|---:|---:|---:|---:|---|---:|---:|---:|"]
    for task in dict.fromkeys(r["task"] for r in plan["runs"]):
        for arm in ARMS:
            planned = [r for r in plan["runs"] if r["task"] == task and r["arm"] == arm]
            completed = [actual[r["runId"]] for r in planned if r["runId"] in actual]
            missing = len(planned) - len(completed)
            contaminated = sum(integrity(r) == "CONTAMINATED" for r in completed)
            unreviewed = sum(integrity(r) == "UNREVIEWED" for r in completed)
            completed = [r for r in completed if eligible(r)]
            successes = [r for r in completed if r["status"] == "SUCCEEDED"]
            known_successes = [r["usage"]["totalTokens"] for r in successes if r["usage"]["allTokenUsageAvailable"]]
            unavailable = sum(not r["usage"]["allTokenUsageAvailable"] for r in completed)
            per_success = None if missing or unavailable or not successes else sum(r["usage"]["totalTokens"] for r in completed) / len(successes)
            middle = median(known_successes) if known_successes else None
            known_all = [r["usage"]["totalTokens"] for r in completed if r["usage"]["allTokenUsageAvailable"]]
            all_summary = f"{median(known_all)} [{min(known_all)}, {max(known_all)}] (계측 {len(known_all)}/{len(completed)})" if known_all else "None"
            elapsed = median(r["elapsedMillis"] for r in completed) if completed else None
            lines.append(f"| {task} | {arm} | {len(successes)}/{len(completed)} | {len(planned)} | {missing} | {contaminated} | {unreviewed} | {all_summary} | {middle} | {elapsed} | {per_success} |")
    lines += ["", "## 전체 시도 기록 — 제외된 실행의 소비도 보존", "",
              "| 실행 | 원시 결과 | 검토 | 총토큰 | 관측된 토큰 |", "|---|---|---|---:|---:|"]
    for item in plan["runs"]:
        r = actual.get(item["runId"])
        lines.append(f'| {item["runId"]} | {r["status"] if r else "NOT_RUN"} | {integrity(r) if r else "UNREVIEWED"} | {r["usage"]["totalTokens"] if r else None} | {r["usage"].get("knownRecordedTokens", r["usage"]["totalTokens"]) if r else None} |')
    lines += ["", "## 같은 과제·반복의 성공 쌍", "", "| 기준 | 비교 가능한 쌍 | 쌍별 절감률 중앙값 |", "|---|---:|---:|"]
    for arm in ("codex", "omx"):
        savings = []
        for r in actual.values():
            if r["arm"] != "oml" or r["status"] != "SUCCEEDED" or not eligible(r):
                continue
            baseline = actual.get(f'{r["task"]}-{r["repeat"]}-{arm}')
            if not baseline or baseline["status"] != "SUCCEEDED" or not eligible(baseline):
                continue
            left, right = baseline["usage"]["totalTokens"], r["usage"]["totalTokens"]
            if left is not None and left > 0 and right is not None:
                savings.append((left - right) / left * 100)
        lines.append(f"| {arm} | {len(savings)} | {median(savings) if savings else None} |")
    fields = ["synthetic", "protocolHash", "runId", "task", "repeat", "arm", "startCommit", "status", "integrity", "comparisonEligible", "reviewHash", "totalTokens", "knownRecordedTokens", "usageComplete", "elapsedMillis", "judgeMillis", "cliExecutions"]
    for item in plan["runs"]:
        r = actual.get(item["runId"])
        rows.append({**item, "synthetic": plan["synthetic"], "protocolHash": plan["protocolHash"],
                     "status": r["status"] if r else "NOT_RUN",
                     "integrity": integrity(r) if r else "UNREVIEWED",
                     "comparisonEligible": eligible(r) if r else False,
                     "reviewHash": digest(reviews[r["runId"]]) if r and r["runId"] in reviews else None,
                     "totalTokens": r["usage"]["totalTokens"] if r else None,
                     "knownRecordedTokens": r["usage"].get("knownRecordedTokens", r["usage"]["totalTokens"]) if r else None,
                     "usageComplete": r["usage"]["allTokenUsageAvailable"] if r else False,
                     "elapsedMillis": r["elapsedMillis"] if r else None,
                     "judgeMillis": r.get("judgeMillis") if r else None,
                     "cliExecutions": r.get("cliExecutions") if r else None})
    csv_buffer = io.StringIO(newline="")
    writer = csv.DictWriter(csv_buffer, fieldnames=fields)
    writer.writeheader()
    writer.writerows(rows)
    return {"markdown": "\n".join(lines) + "\n", "csv": csv_buffer.getvalue()}
