import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

from usage import summarize


def counts(i=100, o=20):
    return {"input_tokens": i, "output_tokens": o, "cached_input_tokens": 50, "reasoning_output_tokens": 5}


class UsageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def session(self, identity, usage=None, **overrides):
        usage = counts() if usage is None else usage
        events = [{"type": "thread.started", "thread_id": identity}, {"type": "turn.started"},
                  {"type": "turn.completed", "usage": usage}]
        return self.log(identity, events, **overrides)

    def log(self, identity, events, **overrides):
        raw = "\n".join(json.dumps(e) for e in events).encode()
        (self.root / (identity + ".jsonl")).write_bytes(raw)
        return {"id": identity, "parentId": None, "scope": "self", "schema": "codex-exec-jsonl-v1",
                "log": identity + ".jsonl", "sha256": hashlib.sha256(raw).hexdigest(), **overrides}

    def result(self, sessions, complete=True):
        return summarize({"inventoryComplete": complete, "sessions": sessions}, self.root)

    def test_self_parent_child_and_duplicate_inventory(self):
        parent = self.session("parent")
        child = self.session("child", parentId="parent")
        result = self.result([parent, child, parent])
        self.assertEqual(240, result["totalTokens"])
        self.assertEqual(100, result["counts"]["cached_input_tokens"])

    def test_inclusive_parent_is_not_double_counted(self):
        self.assertEqual(120, self.result([self.session("p", scope="subtree"),
                                         self.session("c", parentId="p")])["totalTokens"])

    def test_missing_child_log_and_unknown_inventory_are_not_zero(self):
        session = self.session("p")
        self.assertIsNone(self.result([session], complete=False)["totalTokens"])
        child = self.session("c", parentId="p")
        (self.root / "c.jsonl").unlink()
        self.assertIsNone(self.result([session, child])["totalTokens"])

    def test_failed_and_unfinished_turns_are_incomplete(self):
        for event in ({"type": "turn.failed"}, {"type": "turn.started"}):
            session = self.log("p", [{"type": "thread.started", "thread_id": "p"}, event])
            self.assertIsNone(self.result([session])["totalTokens"])

    def test_duplicate_terminal_and_distinct_turns(self):
        done = {"type": "turn.completed", "usage": counts()}
        events = [{"type": "thread.started", "thread_id": "p"}, {"type": "turn.started"}, done, done]
        self.assertEqual(120, self.result([self.log("p", events)])["totalTokens"])
        events += [{"type": "turn.started"}, done]
        self.assertEqual(240, self.result([self.log("p", events)])["totalTokens"])

    def test_completed_usage_survives_later_failure_or_truncated_log(self):
        events = [{"type": "thread.started", "thread_id": "p"}, {"type": "turn.started"},
                  {"type": "turn.completed", "usage": counts()}, {"type": "turn.started"}]
        for tail in ([], [{"type": "turn.failed"}]):
            result = self.result([self.log("p", events + tail)])
            self.assertIsNone(result["totalTokens"])
            self.assertEqual(120, result["knownRecordedTokens"])
        session = self.log("p", events)
        path = self.root / session["log"]
        raw = path.read_bytes() + b'\n{"type":'
        path.write_bytes(raw)
        session["sha256"] = hashlib.sha256(raw).hexdigest()
        result = self.result([session])
        self.assertIsNone(result["totalTokens"])
        self.assertEqual(120, result["knownRecordedTokens"])

    def test_fork_cumulative_excludes_inherited_history(self):
        before = counts(1000, 100)
        event = {"type": "event_msg", "payload": {"type": "token_count", "info": {"total_token_usage": counts(1100, 120)}}}
        metadata = {"type": "session_meta", "payload": {"id": "p"}}
        session = self.log("p", [metadata, event, event], schema="codex-cumulative-v1", initialUsage=before, usageBoundaryVerified=True)
        self.assertEqual(120, self.result([session])["totalTokens"])
        session.pop("initialUsage")
        self.assertIsNone(self.result([session])["totalTokens"])

    def test_counter_reset_scope_cycle_hash_and_invalid_fields(self):
        for overrides in ({"scope": "unknown"}, {"parentId": "p"}, {"sha256": "bad"}, {"schema": "future"}):
            self.assertIsNone(self.result([self.session("p", **overrides)])["totalTokens"])
        for invalid in ({"input_tokens": True, "output_tokens": 1}, counts(-1, 20), counts(1, 1)):
            self.assertIsNone(self.result([self.session("p", invalid)])["totalTokens"])
        event = {"type": "event_msg", "payload": {"type": "token_count", "info": {"total_token_usage": counts()}}}
        metadata = {"type": "session_meta", "payload": {"id": "p"}}
        self.assertIsNone(self.result([self.log("p", [metadata, event], schema="codex-cumulative-v1", initialUsage=counts(200, 30), usageBoundaryVerified=True)])["totalTokens"])

    def test_empty_requires_verified_no_calls(self):
        self.assertIsNone(self.result([])["totalTokens"])
        self.assertEqual(0, summarize({"sessions": [], "inventoryComplete": True, "noModelCallsVerified": True}, self.root)["totalTokens"])

    def test_fifo_and_hardlinked_logs_are_incomplete_without_blocking(self):
        session = self.session("p")
        path = self.root / session["log"]
        path.unlink()
        os.mkfifo(path)
        self.assertIsNone(self.result([session])["totalTokens"])
        path.unlink()
        other = self.session("other")
        os.link(self.root / other["log"], path)
        self.assertIsNone(self.result([session])["totalTokens"])
