import importlib.util
from pathlib import Path
import socket
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("egress", Path(__file__).resolve().parents[1] / "container/egress.py")
egress = importlib.util.module_from_spec(spec)
spec.loader.exec_module(egress)


class EgressTests(unittest.TestCase):
    def test_destinations_are_web_ports_not_host_or_private_addresses(self):
        self.assertEqual(("example.com", 443), egress.authority("example.com:443"))
        self.assertEqual(("2606:4700:4700::1111", 443), egress.authority("[2606:4700:4700::1111]:443"))
        for value in ("x:22", "x:65536", "x:443@127.0.0.1", "x:443/path", "x:443\r\nHost: y", "x:443%00", "", "x:443#f"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                egress.authority(value)
        for value in ("127.0.0.1", "10.1.2.3", "169.254.169.254", "172.17.0.1", "192.168.65.254",
                      "100.64.0.1", "0.0.0.0", "224.0.0.1", "192.0.0.8", "198.18.0.1",
                      "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "2002:7f00:1::", "64:ff9b::7f00:1"):
            self.assertFalse(egress.public_ip(value), value)

    def test_mixed_dns_answers_are_rejected_before_any_connection(self):
        answers = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("1.1.1.1", 443)),
                   (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 443))]
        with patch.object(egress.socket, "getaddrinfo", return_value=answers), patch.object(egress.socket, "socket") as opened:
            with self.assertRaises(ValueError):
                egress.connect_public("rebind.example", 443)
            opened.assert_not_called()

    def test_connect_uses_vetted_numeric_address_without_second_resolution(self):
        answer = (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("1.1.1.1", 443))
        connection = MagicMock()
        with patch.object(egress.socket, "getaddrinfo", return_value=[answer]) as resolver, \
             patch.object(egress.socket, "socket", return_value=connection):
            self.assertIs(connection, egress.connect_public("public.example", 443))
            resolver.assert_called_once()
            connection.connect.assert_called_once_with(("1.1.1.1", 443))
