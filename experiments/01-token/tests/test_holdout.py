"""Public toy inputs exercise storage rules, never the real private tasks."""
from pathlib import Path
import tempfile
import unittest

from fixtures import REPO
from holdout import outside_git, prepare, seal, verify


class HoldoutTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oml-private-contract-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.bundle = self.root / "draft", self.root / "sealed"
        for task in "abc":
            for name, value in {"spec.json": "{}", "judge/Probe.java": "PRIVATE PROBE",
                                "judge/expected.json": "[]", "worker/TASK.md": "PUBLIC REQUEST",
                                "reference/Answer.java": "PRIVATE ANSWER"}.items():
                target = self.source / task / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(value)

    def test_only_task_packet_is_exported_and_bundle_is_pinned(self):
        manifest = seal(self.source, self.bundle)
        self.assertEqual(manifest, verify(self.bundle, manifest["bundleHash"]))
        exported = prepare(self.bundle, "a", self.root / "worker")
        self.assertEqual({"TASK.md", "EXPERIMENT-RULES.md"}, set(exported))
        with self.assertRaises(FileExistsError):
            seal(self.source, self.bundle)

    def test_tampering_and_even_a_resealed_replacement_fail_external_pin(self):
        original = seal(self.source, self.bundle)
        (self.bundle / "a/worker/TASK.md").write_text("changed")
        with self.assertRaisesRegex(ValueError, "changed"):
            verify(self.bundle)
        (self.source / "a/worker/TASK.md").write_text("changed")
        replacement = self.root / "replacement"
        seal(self.source, replacement)
        with self.assertRaisesRegex(ValueError, "changed"):
            verify(replacement, original["bundleHash"])

    def test_git_ancestor_links_and_unhashed_directories_are_rejected(self):
        with self.assertRaises(ValueError):
            outside_git(REPO / "private")
        (self.root / ".git").write_text("gitdir: somewhere")
        with self.assertRaises(ValueError):
            outside_git(self.source)
        (self.root / ".git").unlink()
        (self.source / "linked").symlink_to(self.source / "a/spec.json")
        with self.assertRaises(ValueError):
            seal(self.source, self.bundle)
        (self.source / "linked").unlink()
        (self.source / "build").mkdir()
        with self.assertRaises(ValueError):
            seal(self.source, self.bundle)

    def test_private_material_is_not_an_image_build_input(self):
        dockerfile = (REPO / "experiments/01-token/container/Dockerfile").read_text()
        copies = [line for line in dockerfile.splitlines() if line.startswith(("COPY ", "ADD "))]
        self.assertEqual(["COPY egress.py /opt/oml-preparation/egress.py"], copies)
