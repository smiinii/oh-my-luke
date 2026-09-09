import copy
import csv
import io
import json
from pathlib import Path
import tempfile
import unittest

from report import build_report, protocol_hash, schedule
from review import digest, load_reviews, record_review
from packet import prompt, RULES, rule_hash
from fixtures import prepare


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oml-review-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.plan = {"synthetic": True, "protocolHash": protocol_hash({}), "runs": schedule(("a",), 1)}
        self.records = [{**r, "synthetic": True, "protocolHash": self.plan["protocolHash"], "status": "SUCCEEDED",
                         "elapsedMillis": 10, "usage": {"totalTokens": 100, "allTokenUsageAvailable": True}}
                        for r in self.plan["runs"]]
        self.persist()

    def persist(self):
        (self.root / "results.json").write_text(json.dumps(self.records))
        for record in self.records:
            run = self.root / record["runId"]
            run.mkdir(exist_ok=True)
            (run / "result.json").write_text(json.dumps(record))
            for name in ("execution.json", "stdout.jsonl", "stderr.txt"):
                (run / name).write_text("synthetic evidence")

    def review(self, arm="codex", decision="CLEAR"):
        return record_review(self.root, "a-1-" + arm, decision, "synthetic observed evidence", "test coordinator")

    def report(self):
        return build_report(self.plan, self.records, load_reviews(self.root, self.records))

    def test_unreviewed_is_excluded_without_losing_usage_or_original_success(self):
        self.records[0]["integrity"] = "CLEAR"  # A worker/result claim is not a coordinator review.
        report = self.report()
        rows = list(csv.DictReader(io.StringIO(report["csv"])))
        self.assertTrue(all(r["integrity"] == "UNREVIEWED" and r["comparisonEligible"] == "False" for r in rows))
        self.assertTrue(all(r["totalTokens"] == "100" and r["status"] == "SUCCEEDED" for r in rows))
        self.assertIn("| codex | 0 | None |", report["markdown"])

    def test_confirmed_contamination_excludes_pairs_and_all_comparison_metrics(self):
        self.review("oml")
        self.review("codex", "CONTAMINATED")
        self.review("omx")
        report = self.report()
        self.assertIn("| codex | 0 | None |", report["markdown"])
        self.assertIn("| omx | 1 | 0.0 |", report["markdown"])
        self.assertIn("| a | codex | 0/0 | 1 | 0 | 1 | 0 | None | None | None | None |", report["markdown"])
        self.assertIn("| a-1-codex | SUCCEEDED | CONTAMINATED | 100 | 100 |", report["markdown"])

    def test_later_contamination_appends_but_cannot_be_promoted_or_overwritten(self):
        original = (self.root / "results.json").read_bytes()
        first = self.review()
        path = self.root / "reviews/a-1-codex/000001.json"
        saved = path.read_bytes()
        with self.assertRaises(ValueError):
            self.review()
        second = self.review(decision="CONTAMINATED")
        self.assertEqual(digest(first), second["previousHash"])
        self.assertEqual(saved, path.read_bytes())
        self.assertEqual(original, (self.root / "results.json").read_bytes())
        self.assertEqual("CONTAMINATED", load_reviews(self.root, self.records)["a-1-codex"]["decision"])
        with self.assertRaises(ValueError):
            self.review()

    def test_changed_result_evidence_and_broken_history_are_rejected(self):
        self.review()
        altered = copy.deepcopy(self.records)
        altered[0]["elapsedMillis"] = 11
        with self.assertRaises(ValueError):
            load_reviews(self.root, altered)
        evidence = self.root / "a-1-codex/stdout.jsonl"
        evidence.write_text("changed")
        with self.assertRaises(ValueError):
            self.report()
        evidence.write_text("synthetic evidence")
        self.review(decision="CONTAMINATED")
        (self.root / "reviews/a-1-codex/000001.json").unlink()
        with self.assertRaises(ValueError):
            self.report()

    def test_individual_result_changed_after_review_blocks_reaggregation(self):
        self.review()
        path = self.root / "a-1-codex/result.json"
        modified = dict(self.records[0], status="FAILED")
        path.write_text(json.dumps(modified))
        with self.assertRaisesRegex(ValueError, "Result files disagree"):
            self.report()

    def test_missing_linked_or_worker_evidence_and_unknown_identity_fail_closed(self):
        for identity in ("../a-1-codex", "a-2-codex"):
            with self.assertRaises(ValueError):
                record_review(self.root, identity, "CLEAR", "reason", "reviewer")
        with self.assertRaises(ValueError):
            record_review(self.root, "a-1-codex", "CLEAR", "reason", "reviewer", ["worker/workspace/TASK.md"])
        evidence = self.root / "a-1-codex/stdout.jsonl"
        evidence.unlink()
        with self.assertRaises(FileNotFoundError):
            self.review()
        evidence.symlink_to(self.root / "a-1-omx/stdout.jsonl")
        with self.assertRaises(ValueError):
            self.review()

    def test_failed_and_missing_token_runs_remain_visible(self):
        self.records[0]["status"] = "FAILED"
        self.records[1]["usage"] = {"totalTokens": None, "knownRecordedTokens": 7, "allTokenUsageAvailable": False}
        self.persist()
        for arm in ("codex", "omx", "oml"):
            self.review(arm)
        report = self.report()
        self.assertIn("| codex | 0 | None |", report["markdown"])
        self.assertIn("| omx | 0 | None |", report["markdown"])
        self.assertIn("| a-1-omx | SUCCEEDED | CLEAR | None | 7 |", report["markdown"])
        self.assertIn("FAILED", report["csv"])

    def test_identical_rules_are_in_each_prepared_prompt(self):
        prompts = []
        for arm in ("codex", "omx", "oml"):
            workspace = self.root / ("packet-" + arm)
            baseline = prepare("pilot", workspace)
            self.assertEqual(rule_hash(), baseline["files"]["EXPERIMENT-RULES.md"])
            prompts.append(prompt(workspace))
        self.assertEqual(1, len(set(prompts)))
        self.assertTrue(prompts[0].startswith(RULES.read_text()))
        self.assertIn("로컬 Git 커밋은 허용", prompts[0])
        self.assertIn("모든 비교 실행 종료", prompts[0])

    def test_report_cli_checks_reviews_and_exports_csv_without_overwriting_raw_files(self):
        import subprocess
        import sys
        from fixtures import ROOT
        (self.root / "manifest.json").write_text(json.dumps(self.plan))
        self.review("oml")
        self.review("codex", "CONTAMINATED")
        raw = (self.root / "results.json").read_bytes()
        process = subprocess.run([sys.executable, str(ROOT / "runner/bench.py"), "report", str(self.root), "--format", "csv"],
                                 check=True, capture_output=True, text=True)
        rows = list(csv.DictReader(io.StringIO(process.stdout)))
        self.assertEqual(["CONTAMINATED", "UNREVIEWED", "CLEAR"], [r["integrity"] for r in rows])
        self.assertEqual(raw, (self.root / "results.json").read_bytes())
