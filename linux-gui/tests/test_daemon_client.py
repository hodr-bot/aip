"""Tests for daemon_client against a fake aipd socket server. stdlib only."""

import os
import socket
import tempfile
import threading
import unittest

from daemon_client import DaemonClient, DaemonError


class FakeAipd:
    def __init__(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "aipd.sock")
        self.replies = {
            "STATUS": "status=UP server=testland error=-",
            "CONNECT testland": "OK",
            "DISCONNECT": "OK",
            "LOGIN alice pw": "OK",
            "ALLOWLAN on": "OK",
            "SERVERS": "testland=Testland;demo=Demo, virtual",
            "CONNECT bad": "FAIL dial failed",
        }
        self._stop = threading.Event()
        self.thread = threading.Thread(target=self._serve, daemon=True)

    def start(self):
        self.thread.start()
        for _ in range(100):
            if os.path.exists(self.path):
                return
            threading.Event().wait(0.05)
        raise RuntimeError("fake aipd never bound")

    def stop(self):
        self._stop.set()
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
                s.connect(self.path)
                s.sendall(b"QUIT\n")
        except OSError:
            pass
        self.thread.join(timeout=5)

    def _serve(self):
        if os.path.exists(self.path):
            os.remove(self.path)
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        server.bind(self.path)
        server.listen(5)
        server.settimeout(0.2)
        while not self._stop.is_set():
            try:
                conn, _ = server.accept()
            except socket.timeout:
                continue
            with conn:
                data = b""
                while b"\n" not in data:
                    chunk = conn.recv(1024)
                    if not chunk:
                        break
                    data += chunk
                cmd = data.decode("utf-8").strip()
                conn.sendall((self.replies.get(cmd, "FAIL unknown command") + "\n").encode())
        server.close()


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeAipd()
        self.fake.start()
        self.client = DaemonClient(self.fake.path)

    def tearDown(self):
        self.fake.stop()

    def test_status_parses_kv(self):
        self.assertEqual(
            {"status": "UP", "server": "testland", "error": "-"},
            self.client.status(),
        )

    def test_connect_disconnect(self):
        self.assertEqual("OK", self.client.connect("testland"))
        self.client.disconnect()

    def test_login_and_allow_lan(self):
        self.client.login("alice", "pw")
        self.client.allow_lan(True)

    def test_servers(self):
        self.assertEqual(
            [("testland", "Testland"), ("demo", "Demo, virtual")],
            self.client.servers(),
        )

    def test_fail_raises(self):
        with self.assertRaises(DaemonError):
            self.client.connect("bad")


if __name__ == "__main__":
    unittest.main()
