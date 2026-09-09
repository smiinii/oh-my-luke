import io
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import urllib.request
from unittest.mock import patch

from container_worker import candidate_files, docker, execute_container
from fixtures import prepare


class TransferTests(unittest.TestCase):
    def archive(self, name="workspace/ok.txt", kind=tarfile.REGTYPE, duplicate=False):
        blob = io.BytesIO()
        with tarfile.open(fileobj=blob, mode="w") as archive:
            entry = tarfile.TarInfo(name)
            entry.type = kind
            entry.size = 1 if kind == tarfile.REGTYPE else 0
            archive.addfile(entry, io.BytesIO(b"x"))
            if duplicate:
                archive.addfile(entry, io.BytesIO(b"x"))
        return blob.getvalue()

    def test_normal_transfer(self):
        self.assertEqual({"ok.txt": b"x"}, candidate_files(self.archive()))

    def test_paths_links_devices_duplicates_are_rejected(self):
        for name in ("/workspace/evil", "workspace/../evil", "other/evil"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                candidate_files(self.archive(name))
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                candidate_files(self.archive(kind=kind))
        with self.assertRaises(ValueError):
            candidate_files(self.archive(duplicate=True))


@unittest.skipUnless(os.environ.get("OML_TEST_CONTAINER_IMAGE"), "Explicit Docker integration image required; not proof of isolation")
class ContainerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oml-container-test-")
        self.addCleanup(self.temp.cleanup)
        self.worker = Path(self.temp.name)
        self.baseline = prepare("pilot", self.worker / "workspace")
        self.image = os.environ["OML_TEST_CONTAINER_IMAGE"]

    def run_code(self, code, **kwargs):
        return execute_container(["python3", "-c", code], self.worker, self.image, **kwargs)

    def assert_ok(self, result):
        self.assertEqual("EXITED", result["status"], result)
        self.assertEqual(0, result["exitCode"], result)
        self.assertTrue(result["isolation"]["frozenBeforeExport"])
        self.assertTrue(result["isolation"]["removed"])
        self.assertEqual(self.baseline["startCommit"], result["isolation"]["workerStartCommit"])

    def test_normal_compile_write_and_child_process(self):
        result = self.run_code('''import pathlib, subprocess
p=pathlib.Path('workspace/Hello.java'); p.write_text('class Hello { public static void main(String[] a) { System.out.println("OK"); } }')
subprocess.run(['javac', str(p)], check=True)
assert subprocess.check_output(['java', '-cp', 'workspace', 'Hello']).strip() == b'OK'
''', timeout=30)
        self.assert_ok(result)
        self.assertTrue((self.worker / "workspace/Hello.java").is_file())

    def test_host_peers_credentials_network_and_privilege_denied(self):
        (self.worker / "host-marker").write_text("private")
        result = self.run_code(f'''import os, pathlib, socket
assert not pathlib.Path({str(self.worker / 'host-marker')!r}).exists()
for path in ['/var/run/docker.sock', '/run/host-services', '/Users', '/root/.codex', '/work/../peer']:
    try: assert not pathlib.Path(path).exists(), path
    except PermissionError: pass
assert os.getuid() == 1000
assert not any(os.environ.get(key) for key in ['OPENAI_API_KEY', 'HTTP_PROXY', 'HTTPS_PROXY'])
status=pathlib.Path('/proc/self/status').read_text()
assert 'NoNewPrivs:\\t1' in status
assert 'CapEff:\\t0000000000000000' in status
for family, address in [(socket.AF_INET, ('127.0.0.1', 9)), (socket.AF_INET, ('1.1.1.1', 443)), (socket.AF_INET6, ('2606:4700:4700::1111', 443))]:
    sock=socket.socket(family); sock.settimeout(.2)
    try: sock.connect(address)
    except OSError: pass
    else: raise AssertionError('unexpected network access')
    finally: sock.close()
for path in ['/seed/changed', '/etc/changed']:
    try: pathlib.Path(path).write_text('bad')
    except OSError: pass
    else: raise AssertionError('rootfs writable')
''')
        self.assert_ok(result)

    def test_real_host_relay_and_previous_home_are_unreachable(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'DISPOSABLE-HOST-MARKER')
            def log_message(self, *args):
                pass
        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                url = f'http://127.0.0.1:{server.server_port}/'
                self.assertEqual(b'DISPOSABLE-HOST-MARKER', urllib.request.urlopen(url, timeout=2).read())
                result = self.run_code(f'''import pathlib, urllib.request
for host in ['127.0.0.1', 'host.docker.internal']:
    try: urllib.request.urlopen('http://' + host + ':{server.server_port}/', timeout=1).read()
    except OSError: pass
    else: raise AssertionError('host relay reached')
pathlib.Path('/work/home/previous-session').write_text('PRIVATE-PREVIOUS-RUN')
''')
                self.assert_ok(result)
            finally:
                server.shutdown()
                thread.join()
        self.assert_ok(self.run_code("from pathlib import Path; assert not Path('/work/home/previous-session').exists()"))

    def test_memory_exhaustion_is_not_success_and_is_cleaned(self):
        result = self.run_code("data = bytearray(2 * 1024 * 1024 * 1024)", timeout=15)
        self.assertNotEqual(0, result["exitCode"], result)
        self.assertTrue(result["isolation"]["removed"])

    def test_cleanup_failure_is_recorded_not_silently_successful(self):
        failed = []
        def fail_removal(*args, **kwargs):
            if args[:2] == ("rm", "--force"):
                failed.append(args)
                raise RuntimeError("Injected removal failure")
            if args[:2] == ("volume", "rm"):
                failed.append(args)
                raise RuntimeError("Injected volume removal failure")
            return docker(*args, **kwargs)
        try:
            with patch("container_worker.docker", side_effect=fail_removal):
                result = self.run_code("pass")
            self.assertEqual("ENVIRONMENT_ERROR", result["status"])
            self.assertFalse(result["isolation"]["removed"])
            self.assertEqual(3, len(result["isolation"]["cleanupErrors"]))
        finally:
            for args in failed:
                docker(*args)

    def test_resource_limits_are_enforced(self):
        result = self.run_code('''import pathlib, subprocess
c=pathlib.Path('/sys/fs/cgroup')
assert (c/'memory.max').read_text().strip() == '1073741824'
assert (c/'memory.swap.max').read_text().strip() == '0'
assert (c/'pids.max').read_text().strip() == '64'
quota, period = map(int, (c/'cpu.max').read_text().split()); assert quota / period == 2
children=[]
try:
    for _ in range(100):
        try: children.append(subprocess.Popen(['sleep','30']))
        except BlockingIOError: break
    else: raise AssertionError('PID limit not enforced')
finally:
    for child in children: child.kill(); child.wait()
try:
    with open('/tmp/fill','wb') as f:
        for _ in range(70): f.write(b'x' * 1048576)
except OSError: pass
else: raise AssertionError('tmpfs limit not enforced')
''', timeout=30)
        self.assert_ok(result)

    def test_detached_children_timeout_and_output_cleanup(self):
        for code, options, expected in [
            ("import subprocess; subprocess.Popen(['sleep','30'], start_new_session=True)", {}, "EXITED"),
            ("import time; time.sleep(30)", {"timeout": .2}, "TIMED_OUT"),
            ("print('x'*20000)", {"output_limit": 1000}, "OUTPUT_LIMIT")]:
            result = self.run_code(code, **options)
            self.assertEqual(expected, result["status"], result)
            self.assertTrue(result["isolation"]["removed"])
        self.assertFalse(docker("ps", "-aq", "--filter", "label=io.ohmyluke.preparation=true").strip())

    def test_symlink_export_is_rejected(self):
        result = self.run_code("import pathlib; pathlib.Path('workspace/escape').symlink_to('/etc/passwd')")
        self.assertEqual("ENVIRONMENT_ERROR", result["status"], result)
        self.assertIn("links and special files", result["stderr"])
        self.assertFalse((self.worker / "workspace/escape").exists())
