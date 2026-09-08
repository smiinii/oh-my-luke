"""Conservative session accounting. Inventory/coverage must be proved by adapter."""
import hashlib
import json
from pathlib import Path

FIELDS = ("input_tokens", "output_tokens", "cached_input_tokens", "reasoning_output_tokens")


class IncompleteUsage(ValueError):
    def __init__(self, message, recorded):
        super().__init__(message)
        self.recorded = recorded


def numbers(value):
    if not isinstance(value, dict):
        raise ValueError("Missing usage")
    result = {}
    for key in FIELDS:
        number = value.get(key)
        if key in FIELDS[:2] and number is None:
            raise ValueError("Missing input/output tokens")
        if number is not None and (type(number) is not int or number < 0):
            raise ValueError("Invalid token count")
        result[key] = number
    for child, parent in ((FIELDS[2], FIELDS[0]), (FIELDS[3], FIELDS[1])):
        if result[child] is not None and result[child] > result[parent]:
            raise ValueError("Subset exceeds parent count")
    return result


def add(values):
    return {key: None if any(v[key] is None for v in values) else sum(v[key] for v in values)
            for key in FIELDS}


def parse_exec(events, session_id):
    current = False
    usages = []
    last = None
    seen_thread = False
    for event in events:
        kind = event.get("type")
        if kind == "thread.started":
            if seen_thread or event.get("thread_id") != session_id:
                raise ValueError("Session identity mismatch")
            seen_thread = True
        elif kind == "turn.started":
            if not seen_thread:
                raise ValueError("Turn before session identity")
            if current:
                raise ValueError("Unfinished previous turn")
            current = True
        elif kind == "turn.completed":
            if not current:
                # Identical retransmission of the terminal event, not a new turn.
                if last == event:
                    continue
                raise ValueError("Completion without start")
            usages.append(numbers(event.get("usage")))
            current = False
            last = event
        elif kind in ("turn.failed", "error"):
            raise IncompleteUsage("Failed turn may have unreported usage", add(usages))
    if not seen_thread or current or not usages:
        raise IncompleteUsage("Incomplete execution log", add(usages))
    return add(usages)


def parse_cumulative(events, initial, session_id):
    # initial is the pre-run counter, including copied parent history for forks.
    before = numbers(initial)
    previous = before
    seen = False
    metadata = [e.get("payload", {}).get("id") for e in events if e.get("type") == "session_meta"]
    if metadata != [session_id]:
        raise ValueError("Cumulative session identity mismatch")
    for event in events:
        payload = event.get("payload", {})
        if event.get("type") != "event_msg" or payload.get("type") != "token_count":
            continue
        value = numbers(payload.get("info", {}).get("total_token_usage"))
        for key in FIELDS:
            if previous[key] is not None and value[key] is not None and value[key] < previous[key]:
                raise ValueError("Cumulative counter reset")
        previous = value
        seen = True
    if not seen:
        raise ValueError("No cumulative usage")
    return numbers({key: None if before[key] is None or previous[key] is None else previous[key] - before[key]
                    for key in FIELDS})


def summarize(manifest, log_root):
    log_root = Path(log_root).resolve()
    errors, sessions, fingerprints = [], {}, {}
    if manifest.get("inventoryComplete") is not True:
        errors.append("unverified-session-inventory")
    for session in manifest.get("sessions", []):
        identity = session.get("id")
        if not isinstance(identity, str) or not identity:
            errors.append("invalid-session-id")
            continue
        canonical = json.dumps(session, sort_keys=True)
        if identity in sessions:
            if json.dumps(sessions[identity], sort_keys=True) != canonical:
                errors.append("conflicting-session:" + identity)
            continue
        sessions[identity] = session
    if not sessions and manifest.get("noModelCallsVerified") is not True:
        errors.append("missing-sessions")
    values = {}
    for identity, session in sessions.items():
        try:
            path = (log_root / session["log"]).resolve(strict=True)
            if not path.is_relative_to(log_root) or Path(session["log"]).is_absolute():
                raise ValueError("log-path-escape")
            if path.stat().st_size > 64 * 1024 * 1024:
                raise ValueError("log-size-limit")
            raw = path.read_bytes()
            digest = hashlib.sha256(raw).hexdigest()
            if digest != session.get("sha256"):
                raise ValueError("log-hash-mismatch")
            if digest in fingerprints and fingerprints[digest] != identity:
                raise ValueError("same-log-different-session")
            fingerprints[digest] = identity
            events = []
            for line in raw.splitlines():
                if not line.strip():
                    continue
                try:
                    events.append(json.loads(line))
                except (ValueError, UnicodeError):
                    errors.append(identity + ":truncated-or-invalid-json")
                    break
            if not all(isinstance(e, dict) for e in events):
                raise ValueError("invalid-event")
            if session["schema"] == "codex-exec-jsonl-v1":
                values[identity] = parse_exec(events, identity)
            elif session["schema"] == "codex-cumulative-v1":
                if session.get("usageBoundaryVerified") is not True:
                    raise ValueError("unverified-cumulative-boundary")
                values[identity] = parse_cumulative(events, session.get("initialUsage"), identity)
            else:
                raise ValueError("unsupported-schema")
        except IncompleteUsage as error:
            values[identity] = error.recorded
            errors.append(identity + ":" + str(error))
        except (KeyError, ValueError, OSError, TypeError, AttributeError) as error:
            errors.append(identity + ":" + str(error))
    selected = []
    for identity, session in sessions.items():
        scope = session.get("scope")
        if scope not in ("self", "subtree"):
            errors.append("unknown-coverage:" + identity)
            continue
        covered, parent, visited = False, session.get("parentId"), {identity}
        while parent is not None:
            if not isinstance(parent, str) or parent in visited or parent not in sessions:
                errors.append("invalid-parent-chain:" + identity)
                break
            visited.add(parent)
            covered |= sessions[parent].get("scope") == "subtree"
            parent = sessions[parent].get("parentId")
        if not covered and identity in values:
            selected.append(values[identity])
    counts = add(selected)
    known = counts["input_tokens"] + counts["output_tokens"]
    return {"allTokenUsageAvailable": not errors, "totalTokens": None if errors else known,
            "knownRecordedTokens": known, "counts": counts, "errors": sorted(set(errors)),
            "sessionCount": len(sessions), "modelRequests": None,
            "synthetic": manifest.get("synthetic") is True}
