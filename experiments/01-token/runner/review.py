"""Coordinator-only integrity review; no automatic network/DLP verdict.

Retain immutable revisions and original usage even when excluding a run.
The coordinator and its filesystem are trusted, not worker declarations.
"""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat

STATES = ("CLEAR", "CONTAMINATED")
REQUIRED = ("execution.json", "stdout.jsonl", "stderr.txt")


def digest(record):
    return hashlib.sha256(json.dumps(record, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def safe_read(root, relative):
    root = Path(root).resolve(strict=True)
    path = Path(relative)
    if path.is_absolute() or not path.parts or any(p in ("..", ".") for p in path.parts):
        raise ValueError("Unsafe evidence path")
    target = root
    for part in path.parts:
        target = target / part
        if target.is_symlink():
            raise ValueError("Evidence links are forbidden")
    descriptor = os.open(target, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > 16_000_000:
            raise ValueError("Invalid evidence file")
        data = stream.read(16_000_001)
    if len(data) > 16_000_000:
        raise ValueError("Evidence too large")
    return data


def run_id(value):
    if not re.fullmatch(r"[a-z]+-[1-9][0-9]*-(?:codex|omx|oml)", value):
        raise ValueError("Invalid review run ID")
    return value


def validate(review, record):
    if (review.get("schema") != 1 or review.get("decision") not in STATES or review.get("runId") != record["runId"]
            or review.get("recordHash") != digest(record)
            or review.get("protocolHash") != record["protocolHash"]):
        raise ValueError("Review identity/result mismatch")
    for field in ("reason", "reviewer"):
        value = review.get(field)
        if not isinstance(value, str) or not value.strip() or len(value) > 1000 or "\n" in value or "\r" in value:
            raise ValueError("Review requires concise reviewer and reason")
    evidence = review.get("evidence", {})
    if not set(REQUIRED).issubset(evidence) or any(not re.fullmatch(r"[a-f0-9]{64}", h) for h in evidence.values()):
        raise ValueError("Review evidence missing")
    if not isinstance(review.get("reviewedAt"), str) or datetime.fromisoformat(review["reviewedAt"]).tzinfo is None:
        raise ValueError("Review timestamp must include timezone")


def history(root, record):
    identity = run_id(record["runId"])
    directory = Path(root) / "reviews" / identity
    if directory.is_symlink() or directory.parent.is_symlink():
        raise ValueError("Review directory links forbidden")
    if not directory.exists():
        return []
    if json.loads(safe_read(root, identity + "/result.json")) != record:
        raise ValueError("Result files disagree")
    result, previous = [], None
    for index, path in enumerate(sorted(directory.iterdir()), 1):
        if path.name != f"{index:06d}.json":
            raise ValueError("Invalid review history sequence")
        entry = json.loads(safe_read(root, str(path.relative_to(root))))
        validate(entry, record)
        if entry.get("previousHash") != previous or (result and (result[-1]["decision"] != "CLEAR" or entry["decision"] != "CONTAMINATED")):
            raise ValueError("Invalid review history transition")
        for name, expected in entry["evidence"].items():
            if name not in (*REQUIRED, "review-evidence.txt"):
                raise ValueError("Only coordinator evidence allowed")
            if hashlib.sha256(safe_read(root, identity + "/" + name)).hexdigest() != expected:
                raise ValueError("Review evidence changed")
        result.append(entry)
        previous = digest(entry)
    return result


def load_reviews(root, records):
    root = Path(root).resolve(strict=True)
    known = {run_id(r["runId"]) for r in records}
    directory = root / "reviews"
    if directory.is_symlink():
        raise ValueError("Review directory link forbidden")
    if directory.exists() and any(p.name not in known or not p.is_dir() for p in directory.iterdir()):
        raise ValueError("Unknown review run")
    return {r["runId"]: entries[-1] for r in records if (entries := history(root, r))}


def record_review(root, identity, decision, reason, reviewer, evidence=()):
    root = Path(root).resolve(strict=True)
    identity = run_id(identity)
    records = json.loads(safe_read(root, "results.json"))
    matches = [r for r in records if r["runId"] == identity]
    if len(matches) != 1:
        raise ValueError("Review requires exactly one completed attempt record")
    record = matches[0]
    if json.loads(safe_read(root, identity + "/result.json")) != record:
        raise ValueError("Result files disagree")
    entries = history(root, record)
    if entries and (entries[-1]["decision"] != "CLEAR" or decision != "CONTAMINATED"):
        raise ValueError("No overwrite or promotion of contaminated attempts; keep original attempt")
    names = sorted(set(REQUIRED) | set(evidence))
    if any(name not in (*REQUIRED, "review-evidence.txt") for name in names):
        raise ValueError("Only coordinator evidence allowed")
    entry = {"schema": 1, "runId": identity, "decision": decision, "reason": reason, "reviewer": reviewer,
             "recordHash": digest(record), "protocolHash": record["protocolHash"],
             "reviewedAt": datetime.now(timezone.utc).isoformat(),
             "previousHash": digest(entries[-1]) if entries else None,
             "evidence": {name: hashlib.sha256(safe_read(root, identity + "/" + name)).hexdigest() for name in names}}
    validate(entry, record)
    directory = root / "reviews" / identity
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / f"{len(entries)+1:06d}.json").open("x") as stream:
        json.dump(entry, stream, indent=2, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    return entry
