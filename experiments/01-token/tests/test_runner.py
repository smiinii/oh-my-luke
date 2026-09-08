import json
from pathlib import Path
import tempfile
import unittest

from bench import dry_run
from report import build_report


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
