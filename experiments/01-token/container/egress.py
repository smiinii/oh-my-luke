"""Public HTTP(S) gateway over a Unix socket; no host or peer mounts.

This is a network-address boundary, NOT an Internet content/DLP filter.
An arbitrary public service can still be used to publish or retrieve answers.
"""
from http.server import BaseHTTPRequestHandler
import ipaddress
import json
import os
from pathlib import Path
import re
import select
import socket
import socketserver
import sys
import threading
import time
from urllib.parse import urlsplit

SOCKET = "/gateway/proxy.sock"
BLOCKED_V4 = tuple(ipaddress.ip_network(cidr) for cidr in (
    "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
    "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15",
    "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4"))


def authority(value):
    if not value.isascii() or len(value) > 260 or any(c in value for c in "/?#@\\ \t\r\n%"):
        raise ValueError("Invalid authority")
    parsed = urlsplit("//" + value)
    host, port = parsed.hostname, parsed.port
    if not host or port not in (80, 443) or not re.fullmatch(r"[a-zA-Z0-9.:-]+", host):
        raise ValueError("Only HTTP(S) destination ports allowed")
    return host, port


def public_ip(value):
    ip = ipaddress.ip_address(value)
    if not ip.is_global or ip.is_multicast:
        return False
    if ip.version == 4:
        return not any(ip in network for network in BLOCKED_V4)
    return (ip in ipaddress.ip_network("2000::/3") and not ip.sixtofour and not ip.teredo
            and ip not in ipaddress.ip_network("2001::/23"))


def connect_public(host, port):
    answers = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    if not answers or any(not public_ip(answer[4][0]) for answer in answers):
        raise ValueError("Non-public destination")
    last = None
    for family, kind, protocol, _, address in answers:
        upstream = socket.socket(family, kind, protocol)
        upstream.settimeout(10)
        try:
            # Exact validated numeric sockaddr; no second DNS lookup / rebinding gap.
            upstream.connect(address)
            return upstream
        except OSError as error:
            upstream.close()
            last = error
    raise last


def relay(left, right):
    deadline, transferred = time.monotonic() + 1200, 0
    while time.monotonic() < deadline:
        ready, _, _ = select.select([left, right], [], [], 30)
        if not ready:
            return
        for source in ready:
            data = source.recv(65536)
            if not data:
                return
            transferred += len(data)
            if transferred > 512 * 1024 * 1024:
                return
            (right if source is left else left).sendall(data)


class Proxy(BaseHTTPRequestHandler):
    # Do not buffer bytes after CONNECT headers away from the socket relay.
    rbufsize = 0
    protocol_version = "HTTP/1.1"

    def setup(self):
        self.request.settimeout(10)
        super().setup()

    def log_message(self, *args):
        pass  # Never log URL paths, request headers, payloads or credentials.

    def do_CONNECT(self):
        try:
            host, port = authority(self.path)
            upstream = connect_public(host, port)
        except (OSError, ValueError):
            self.send_error(403, "Destination denied or unavailable")
            return
        with upstream:
            self.send_response(200, "Connection established")
            self.end_headers()
            try:
                relay(self.connection, upstream)
            except OSError:
                pass
        self.close_connection = True

    def forward_http(self):
        try:
            target = urlsplit(self.path)
            if target.scheme != "http" or target.username or target.password or target.fragment:
                raise ValueError("Absolute HTTP URL required")
            # Brackets retained for IPv6 literals.
            endpoint = target.netloc if target.port else target.netloc + ":80"
            host, port = authority(endpoint)
            if self.headers.get("Transfer-Encoding") or len(self.headers.get_all("Content-Length", [])) > 1:
                raise ValueError("Unsupported request framing")
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 <= size <= 16 * 1024 * 1024 or len(str(self.headers)) > 65536:
                raise ValueError("Request limit")
            body = self.rfile.read(size)
            if len(body) != size:
                raise ValueError("Truncated body")
            upstream = connect_public(host, port)
            path = target.path or "/"
            if target.query:
                path += "?" + target.query
            headers = "".join(f"{key}: {value}\r\n" for key, value in self.headers.items()
                              if key.lower() not in ("host", "connection", "proxy-connection", "proxy-authorization", "keep-alive"))
            request = f"{self.command} {path} HTTP/1.0\r\nHost: {target.netloc}\r\nConnection: close\r\n{headers}\r\n".encode("latin1")
        except (OSError, ValueError):
            self.send_error(403, "Destination denied or unavailable")
            return
        with upstream:
            try:
                upstream.sendall(request + body)
                total = 0
                while data := upstream.recv(65536):
                    total += len(data)
                    if total > 512 * 1024 * 1024:
                        break
                    self.connection.sendall(data)
            except OSError:
                pass
        self.close_connection = True

    do_GET = do_HEAD = do_POST = do_PUT = do_PATCH = do_DELETE = do_OPTIONS = forward_http


class LimitedThreads(socketserver.ThreadingMixIn):
    daemon_threads = True
    slots = threading.BoundedSemaphore(16)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        super().process_request(request, address)

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class UnixProxy(LimitedThreads, socketserver.UnixStreamServer):
    pass


class Forwarder(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        with socket.socket(socket.AF_UNIX) as upstream:
            upstream.settimeout(10)
            try:
                upstream.connect(SOCKET)
                relay(self.request, upstream)
            except OSError:
                pass


class LocalProxy(LimitedThreads, socketserver.TCPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    if sys.argv[1:] == ["gateway"]:
        with UnixProxy(SOCKET, Proxy) as server:
            os.chmod(SOCKET, 0o666)
            server.serve_forever()
    elif sys.argv[1:] == ["forward"]:
        with LocalProxy(("127.0.0.1", 18080), Forwarder) as server:
            Path("/work/proxy-ready").touch()
            server.serve_forever()
    else:
        raise SystemExit("Expected gateway or forward")
