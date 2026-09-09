import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from bench import dry_run
from report import build_report
from fixtures import prepare


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oml-runner-tests-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_end_to_end_fake_result_can_be_rebuilt_without_models(self):
        output = self.root / "success"
        records = dry_run(output)
        self.assertEqual(3, len(records))
        self.assertEqual({"SUCCEEDED"}, {r["status"] for r in records})
        self.assertEqual({0}, {r["actualAiCalls"] for r in records})
        self.assertEqual(1, len({r["startCommit"] for r in records}))
        rebuilt = build_report(json.loads((output / "manifest.json").read_text()),
                               json.loads((output / "results.json").read_text()))
        self.assertEqual(rebuilt["markdown"], (output / "report.md").read_text())
        self.assertEqual(rebuilt["csv"].replace("\r\n", "\n"), (output / "summary.csv").read_text())
        with self.assertRaises(FileExistsError):
            dry_run(output)

    def test_failure_timeout_and_environment_errors_are_retained(self):
        for mode, expected in (("fail", "FAILED"), ("timeout", "TIMED_OUT"),
                               ("environment_error", "ENVIRONMENT_ERROR")):
            with self.subTest(mode=mode):
                records = dry_run(self.root / mode, mode=mode)
                self.assertEqual({expected}, {r["status"] for r in records})
                self.assertTrue(all(r["usage"]["totalTokens"] is None for r in records))

    def test_cleanup_failure_retains_attempt_and_stops_other_arms(self):
        baseline = prepare("pilot", self.root / "expected")
        outcome = {"status": "ENVIRONMENT_ERROR", "exitCode": 0, "elapsedMillis": 1,
                   "stdout": "", "stderr": "Cleanup incomplete",
                   "isolation": {"removed": False, "workerStartCommit": baseline["startCommit"]}}
        with patch("bench.image_identity", return_value={"imageId": "test-image"}), \
             patch("bench.execute_container", return_value=outcome) as executor:
            output = self.root / "cleanup-failure"
            records = dry_run(output, container_image="test-image")
        self.assertEqual(1, executor.call_count)
        self.assertEqual("ENVIRONMENT_ERROR", records[0]["status"])
        self.assertFalse(json.loads((output / "pilot-1-codex/execution.json").read_text())["isolation"]["removed"])
        self.assertEqual(2, (output / "summary.csv").read_text().count("NOT_RUN"))
