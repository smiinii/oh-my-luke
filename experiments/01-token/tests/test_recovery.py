import json
import os
from pathlib import Path
import tempfile
import unittest

from recovery import LABEL, Lease, read_record, recover_abandoned


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oml-recovery-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def missing(self, *args):
        raise RuntimeError("No such " + args[0] + ": " + args[-1])

    def test_live_lock_is_not_recovered(self):
        lease = Lease(self.missing, self.root)
        try:
            self.assertEqual([], recover_abandoned(self.missing, self.root))
            self.assertEqual("ACTIVE", read_record(lease.path)["state"])
        finally:
            lease.finish([])

    def test_crash_before_creation_becomes_interrupted_not_zero_usage(self):
        lease = Lease(self.missing, self.root)
        os.close(lease.descriptor)
        result = recover_abandoned(self.missing, self.root)
        self.assertEqual("INTERRUPTED", result[0]["result"])
        self.assertIsNone(result[0]["totalTokens"])
        self.assertFalse(result[0]["allTokenUsageAvailable"])
        self.assertEqual([], recover_abandoned(self.missing, self.root))

    def test_unavailable_engine_denies_new_admission_and_preserves_record(self):
        lease = Lease(self.missing, self.root)
        os.close(lease.descriptor)
        def offline(*args):
            raise RuntimeError("Cannot connect to the Docker daemon")
        with self.assertRaisesRegex(RuntimeError, "admission denied"):
            Lease(offline, self.root)
        self.assertEqual(1, len(list(self.root.glob("*.json"))))
        self.assertEqual("CLEANUP_FAILED", read_record(lease.path)["state"])
        self.assertEqual("CLEANED", recover_abandoned(self.missing, self.root)[0]["state"])

    def test_foreign_resource_never_removed(self):
        lease = Lease(self.missing, self.root)
        os.close(lease.descriptor)
        calls = []
        def foreign(*args):
            calls.append(args)
            if args[:2] == ("container", "inspect"):
                return json.dumps([{"Id": "foreign", "Config": {"Labels": {LABEL: "other"}}}]).encode()
            return self.missing(*args)
        with self.assertRaisesRegex(RuntimeError, "ownership mismatch"):
            recover_abandoned(foreign, self.root)
        self.assertFalse(any(args[0] == "rm" for args in calls))

    def test_late_daemon_creation_is_rechecked_even_after_cleanup(self):
        lease = Lease(self.missing, self.root)
        lease.finish([])
        pending = {lease.name}
        def late(*args):
            if args[:2] == ("container", "inspect") and args[-1] in pending:
                return json.dumps([{"Id": "late-id", "Config": {"Labels": {LABEL: lease.id}}}]).encode()
            if args == ("rm", "--force", "late-id"):
                pending.clear()
                return b""
            return self.missing(*args)
        self.assertEqual("INTERRUPTED", recover_abandoned(late, self.root)[0]["result"])
        self.assertFalse(pending)
        self.assertEqual([], recover_abandoned(late, self.root))

    def test_symlink_journal_is_rejected(self):
        target = self.root / "marker"
        target.write_text("private")
        (self.root / ("a" * 32 + ".json")).symlink_to(target)
        with self.assertRaises(OSError):
            recover_abandoned(self.missing, self.root)
        self.assertEqual("private", target.read_text())
