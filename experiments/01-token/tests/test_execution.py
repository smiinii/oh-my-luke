import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import threading
import unittest

from execution import execute


class IsolationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oml-isolation-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.worker = self.root / "worker"
        self.worker.mkdir()
        self.peer = self.root / "peer"
        self.peer.mkdir()
        (self.peer / "answer.txt").write_text("must-not-read")
        self.common = tempfile.TemporaryDirectory(prefix="oml-common-")
        self.addCleanup(self.common.cleanup)
        self.common_file = Path(self.common.name).resolve() / "common.txt"
        self.common_file.write_text("common-project-context")

    def run_code(self, code, **kwargs):
        return execute([sys.executable, "-c", code], self.worker, [self.root], **kwargs)

    def test_real_own_write_common_read_peer_and_symlink_denial(self):
        (self.worker / "alias").symlink_to(self.peer, target_is_directory=True)
        code = f'''from pathlib import Path
assert Path({str(self.common_file)!r}).read_text() == "common-project-context"
Path("own.txt").write_text("ok")
for target in [{str(self.peer / 'answer.txt')!r}, "alias/answer.txt", "../peer/answer.txt"]:
    try: Path(target).read_text()
    except OSError: pass
    else: raise AssertionError("peer readable: " + target)
try: Path({str(self.common_file)!r}).write_text("bad")
except OSError: pass
else: raise AssertionError("external write allowed")
print("ISOLATION_OK")
'''
        outcome = self.run_code(code)
        self.assertEqual(0, outcome["exitCode"], outcome)
        self.assertIn("ISOLATION_OK", outcome["stdout"])
        self.assertEqual("common-project-context", self.common_file.read_text())

    def test_child_cannot_read_peer(self):
        code = f'''import subprocess
p=subprocess.run(["/bin/cat", {str(self.peer / 'answer.txt')!r}],capture_output=True)
assert p.returncode != 0
'''
        self.assertEqual(0, self.run_code(code)["exitCode"])

    def test_worker_cannot_signal_host_coordinator(self):
        code = f'''import os
try: os.kill({os.getpid()}, 0)
except (PermissionError, ProcessLookupError): pass
else: raise AssertionError("host coordinator can be signalled")
'''
        result = self.run_code(code)
        self.assertEqual(0, result["exitCode"], result)

    @unittest.skipUnless(sys.platform == "darwin", "macOS data volume alias")
    def test_macos_volume_alias_cannot_read_peer(self):
        alias = Path("/System/Volumes/Data") / str(self.peer / "answer.txt").lstrip("/")
        if not alias.is_file():
            self.skipTest("No separate data volume alias on this host")
        result = self.run_code(f'''from pathlib import Path
try: Path({str(alias)!r}).read_text()
except OSError: pass
else: raise AssertionError("data-volume alias readable")
''')
        self.assertEqual(0, result["exitCode"], result)

    def test_common_network_capability_is_not_disabled(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"public-common-context")

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                code = f'''import urllib.request
assert urllib.request.urlopen("http://127.0.0.1:{server.server_port}/", timeout=3).read() == b"public-common-context"
'''
                self.assertEqual(0, self.run_code(code)["exitCode"])
            finally:
                server.shutdown()
                thread.join()

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux PID namespace guarantee only")
    def test_linux_detached_child_cannot_outlive_namespace(self):
        code = '''import subprocess,sys
subprocess.Popen([sys.executable,"-c","import time,pathlib; time.sleep(1); pathlib.Path('detached.txt').write_text('bad')"],start_new_session=True)
'''
        self.assertEqual(0, self.run_code(code)["exitCode"])
        time.sleep(1.1)
        self.assertFalse((self.worker / "detached.txt").exists())

    def test_timeout_kills_child_and_does_not_wait_on_inherited_stdout(self):
        code = '''import subprocess,time
subprocess.Popen(["/bin/sh","-c","sleep 1; echo escaped > orphan.txt"])
time.sleep(20)
'''
        outcome = self.run_code(code, timeout=0.2)
        self.assertEqual("TIMED_OUT", outcome["status"])
        time.sleep(1.1)
        self.assertFalse((self.worker / "orphan.txt").exists())

    def test_successful_leader_also_cleans_children(self):
        code = '''import subprocess
subprocess.Popen(["/bin/sh","-c","sleep 1; echo escaped > orphan.txt"])
'''
        self.assertEqual(0, self.run_code(code)["exitCode"])
        time.sleep(1.1)
        self.assertFalse((self.worker / "orphan.txt").exists())

    def test_output_limit_and_sensitive_environment(self):
        outcome = self.run_code('import sys; sys.stdout.write("x"*20000)', output_limit=1000)
        self.assertEqual("OUTPUT_LIMIT", outcome["status"])
        os.environ["OML_TEST_SECRET"] = "do-not-pass"
        try:
            outcome = self.run_code('import os; assert "OML_TEST_SECRET" not in os.environ')
            self.assertEqual(0, outcome["exitCode"])
        finally:
            del os.environ["OML_TEST_SECRET"]

    def test_bad_hidden_path_and_missing_executable_fail(self):
        with self.assertRaises(ValueError):
            execute(["/bin/true"], self.worker, [self.worker])
        outcome = execute(["/nonexistent/oml-fixture"], self.worker, [self.root])
        self.assertNotEqual(0, outcome["exitCode"])
