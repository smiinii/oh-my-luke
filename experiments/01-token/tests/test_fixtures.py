from pathlib import Path
import tempfile
import unittest

from evaluator import evaluate
from fixtures import apply_reference, prepare, snapshot


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oml-fixture-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self, task):
        path = self.root / task
        return path, prepare(task, path)

    def test_same_commit_independent_git_and_no_reference_leak(self):
        one = prepare("a", self.root / "one")
        two = prepare("a", self.root / "two")
        self.assertEqual(one["startCommit"], two["startCommit"])
        self.assertEqual(one["files"], two["files"])
        self.assertTrue((self.root / "one/.git").is_dir())
        self.assertFalse((self.root / "one/references").exists())
        with self.assertRaises(FileExistsError):
            prepare("a", self.root / "one")

    def test_originals_fail_and_references_pass_for_all_tasks(self):
        for task in ("a", "b", "c", "pilot"):
            with self.subTest(task=task):
                path, baseline = self.fixture(task)
                self.assertEqual("FAILED", evaluate(task, path, baseline)["status"])
                apply_reference(task, path)
                result = evaluate(task, path, baseline)
                self.assertEqual("SUCCEEDED", result["status"], result)

    def test_deletion_build_tampering_and_symlink_rejected(self):
        path, baseline = self.fixture("pilot")
        apply_reference("pilot", path)
        settings = path / "settings.gradle.kts"
        settings.write_text("// disabled")
        self.assertEqual("forbidden-change", evaluate("pilot", path, baseline)["reason"])
        settings.unlink()
        self.assertEqual("forbidden-change", evaluate("pilot", path, baseline)["reason"])
        settings.symlink_to(self.root / "missing")
        self.assertEqual("invalid-tree", evaluate("pilot", path, baseline)["reason"])

    def test_vacuous_test_and_wrong_feature_are_rejected(self):
        path, baseline = self.fixture("a")
        apply_reference("a", path)
        regression = path / "src/test/java/benchmark/order/RegressionChecks.java"
        regression.write_text('package benchmark.order; public class RegressionChecks { public static void main(String[] args) {} }')
        self.assertEqual("vacuous-regression-test", evaluate("a", path, baseline)["reason"])
        apply_reference("a", path)
        calc = path / "src/main/java/benchmark/order/OrderCalculator.java"
        calc.write_text(calc.read_text().replace(">= 50_000", ">= 50_001"))
        self.assertEqual("FAILED", evaluate("a", path, baseline)["status"])

    def test_valid_alternative_implementation_passes(self):
        path, baseline = self.fixture("b")
        apply_reference("b", path)
        calc = path / "src/main/java/benchmark/order/OrderCalculator.java"
        calc.write_text(calc.read_text().replace("subtotal - Math.min(subtotal, discountAmount)",
                                                "(discountAmount >= subtotal ? 0 : subtotal - discountAmount)"))
        result = evaluate("b", path, baseline)
        self.assertEqual("SUCCEEDED", result["status"], result)

    def test_comment_only_validator_reference_is_rejected(self):
        path, baseline = self.fixture("c")
        original = (path / "src/main/java/benchmark/order/CheckoutService.java").read_text()
        apply_reference("c", path)
        (path / "src/main/java/benchmark/order/CheckoutService.java").write_text(original + "\n// OrderLineValidator.validate(line)\n")
        self.assertEqual("missing-validator-call", evaluate("c", path, baseline)["reason"])
