"""Adversarial checks use only disposable marker files, never user secrets."""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import sys
import threading
from pathlib import Path
import tempfile
import unittest

from evaluator import evaluate
from fixtures import apply_reference, prepare
from execution import execute
from readiness import live_readiness, require_live_ready
from unittest.mock import patch


class EvaluationBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="oml-audit-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.candidate = self.root / "candidate"
        self.baseline = prepare("pilot", self.candidate)

    def added_test(self, body):
        path = self.candidate / "src/test/java/benchmark/order/AttackChecks.java"
        path.write_text('package benchmark.order; public class AttackChecks { '
                        'public static void main(String[] args) throws Exception { ' + body + ' }}')

    def test_candidate_test_cannot_read_sibling_answer(self):
        apply_reference("pilot", self.candidate)
        marker = self.root / "peer-answer.txt"
        marker.write_text("DISPOSABLE-ANSWER")
        self.added_test('try { java.nio.file.Files.readString(java.nio.file.Path.of('
                        + json.dumps(str(marker)) + ')); } catch (java.io.IOException expected) { return; } '
                        'throw new AssertionError("peer answer readable from evaluator");')
        result = evaluate("pilot", self.candidate, self.baseline)
        self.assertEqual("SUCCEEDED", result["status"], result)

    def test_candidate_test_cannot_overwrite_evaluator_bytecode(self):
        # Leave the broken QuantityLabel unchanged. The test replaces the judge.
        source = 'package benchmark.order; public class Judge { public static void main(String[] a) { System.out.println("JUDGE_PASS:"+a[1]); }}'
        self.added_test('java.nio.file.Files.writeString(java.nio.file.Path.of("Judge.java"), '
                        + json.dumps(source) + '); '
                        'int code = javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d","classes","Judge.java"); '
                        'if (code != 0) throw new AssertionError("compile");')
        result = evaluate("pilot", self.candidate, self.baseline)
        self.assertNotEqual("SUCCEEDED", result["status"], result)

    def test_actual_classpath_is_read_only(self):
        apply_reference("pilot", self.candidate)
        self.added_test('try { java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("java.class.path"), "Judge.class"),new byte[]{0}); } '
                        'catch(java.io.IOException expected) { return; } throw new AssertionError("judge writable");')
        result = evaluate("pilot", self.candidate, self.baseline)
        self.assertEqual("SUCCEEDED", result["status"], result)

    def test_special_files_and_hardlinks_are_rejected_without_hanging(self):
        path = self.candidate / "src/main/java/benchmark/order/Injected.java"
        os.mkfifo(path)
        self.assertEqual("invalid-tree", evaluate("pilot", self.candidate, self.baseline)["reason"])
        path.unlink()
        marker = self.root / "peer-marker.txt"
        marker.write_text("DISPOSABLE")
        os.link(marker, path)
        self.assertEqual("invalid-tree", evaluate("pilot", self.candidate, self.baseline)["reason"])

    def test_host_git_environment_cannot_redirect_fixture(self):
        with patch.dict(os.environ, {"GIT_DIR": str(self.candidate / ".git"),
                                     "GIT_WORK_TREE": str(self.candidate), "GIT_CONFIG_COUNT": "1",
                                     "GIT_CONFIG_KEY_0": "core.bare", "GIT_CONFIG_VALUE_0": "true"}):
            new = self.root / "independent"
            other = prepare("pilot", new)
        self.assertEqual(self.baseline["startCommit"], other["startCommit"])
        self.assertTrue((new / ".git").is_dir())

    def test_evaluated_code_cannot_use_host_http_relay(self):
        marker = self.root / "relay-secret.txt"
        marker.write_text("DISPOSABLE-RELAY-MARKER")
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(marker.read_bytes())
            def log_message(self, *args):
                pass
        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                apply_reference("pilot", self.candidate)
                self.added_test('try { var socket = new java.net.Socket(); '
                                'socket.connect(new java.net.InetSocketAddress("127.0.0.1", '
                                + str(server.server_port) + '), 500); socket.close(); } '
                                'catch(java.io.IOException expected) { return; } '
                                'throw new AssertionError("host HTTP relay reachable");')
                result = evaluate("pilot", self.candidate, self.baseline)
                self.assertEqual("SUCCEEDED", result["status"], result)
                # Deliberately prove the open-network worker is NOT live-safe.
                # This is a known-limit test, not a passing isolation claim.
                worker = self.root / "worker"
                worker.mkdir()
                probe = execute([sys.executable, "-c", 'import urllib.request; '
                                 f'print(urllib.request.urlopen("http://127.0.0.1:{server.server_port}/", timeout=2).read().decode())'],
                                worker, [self.root])
                self.assertEqual(0, probe["exitCode"], probe)
                self.assertIn("DISPOSABLE-RELAY-MARKER", probe["stdout"])
                self.assertFalse(live_readiness()["liveReady"])
                with self.assertRaisesRegex(RuntimeError, "LIVE_CONTAINER_ADAPTER"):
                    require_live_ready()
            finally:
                server.shutdown()
                thread.join()

    def test_candidate_cannot_read_any_other_evaluation_workspace(self):
        apply_reference("pilot", self.candidate)
        with tempfile.TemporaryDirectory(prefix="oml-other-evaluation-") as other:
            marker = Path(other).resolve() / "answer.txt"
            marker.write_text("OTHER-EVALUATION")
            self.added_test('try { java.nio.file.Files.readString(java.nio.file.Path.of('
                            + json.dumps(str(marker)) + ')); } catch (java.io.IOException expected) { return; } '
                            'throw new AssertionError("other evaluator readable");')
            result = evaluate("pilot", self.candidate, self.baseline)
            self.assertEqual("SUCCEEDED", result["status"], result)

    def test_flooded_candidate_directory_is_rejected(self):
        base = self.candidate / "extra"
        base.mkdir()
        for index in range(2001):
            (base / str(index)).mkdir()
        self.assertEqual("invalid-tree", evaluate("pilot", self.candidate, self.baseline)["reason"])

    def test_candidate_cannot_forge_pass_marker_and_exit(self):
        path = self.candidate / "src/main/java/benchmark/order/QuantityLabel.java"
        path.write_text('''package benchmark.order;
public class QuantityLabel {
 static { String[] a = System.getProperty("sun.java.command", "").split(" ");
   if(a.length >= 2) { System.out.println("JUDGE_PASS:" + a[a.length-1]); System.exit(0); }
 }
 public String label(int quantity) { return "items"; }
}
''')
        result = evaluate("pilot", self.candidate, self.baseline)
        self.assertNotEqual("SUCCEEDED", result["status"], result)
