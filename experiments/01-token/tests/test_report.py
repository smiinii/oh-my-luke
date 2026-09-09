import copy
import unittest
from report import build_report, protocol_hash, schedule


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.plan = {"synthetic": True, "protocolHash": protocol_hash({"model": "fake"}), "runs": schedule(("a",), 1)}
        self.records = [{**r, "synthetic": True, "protocolHash": self.plan["protocolHash"],
                         "status": "SUCCEEDED", "elapsedMillis": 10,
                         "usage": {"totalTokens": 100, "allTokenUsageAvailable": True}} for r in self.plan["runs"]]

    def test_balanced_27_run_order(self):
        runs = schedule()
        self.assertEqual(27, len(runs))
        for task in ("a", "b", "c"):
            starts = [r["arm"] for r in runs if r["task"] == task][::3]
            self.assertEqual({"codex", "omx", "oml"}, set(starts))

    def test_deterministic_report_and_missing_rows(self):
        first = build_report(self.plan, self.records[:1])
        self.assertEqual(first, build_report(self.plan, self.records[:1]))
        self.assertIn("NOT_RUN", first["csv"])
        self.assertIn("SYNTHETIC", first["markdown"])

    def test_no_failed_or_unmeasured_run_in_savings(self):
        self.records[0]["status"] = "FAILED"
        self.records[1]["usage"] = {"totalTokens": None, "allTokenUsageAvailable": False}
        report = build_report(self.plan, self.records)
        self.assertIn("| codex | 0 | None |", report["markdown"])
        self.assertIn("| omx | 0 | None |", report["markdown"])

    def test_duplicate_mixed_identity_and_negative_values_rejected(self):
        with self.assertRaises(ValueError):
            build_report(self.plan, self.records + [self.records[0]])
        for key, value in (("synthetic", False), ("protocolHash", "different"), ("arm", "oml")):
            records = copy.deepcopy(self.records)
            records[0][key] = value
            with self.assertRaises(ValueError):
                build_report(self.plan, records)
        self.records[0]["usage"]["totalTokens"] = -1
        with self.assertRaises(ValueError):
            build_report(self.plan, self.records)

    def test_changed_start_commit_is_rejected(self):
        self.plan["runs"][0]["startCommit"] = "expected"
        self.records[0]["startCommit"] = "changed"
        with self.assertRaises(ValueError):
            build_report(self.plan, self.records)
