"""Talk to piad over its Unix socket. One command per connection, one line back.

Protocol (see linux-daemon PiadServer):
  STATUS            -> status=UP server=testland error=-
  CONNECT [server]  -> OK | FAIL <reason>
  DISCONNECT        -> OK
  LOGIN user pass   -> OK | FAIL ...
  ALLOWLAN on|off   -> OK | FAIL ...
  SERVERS           -> id=Title;id=Title | FAIL ...
"""

import socket


class DaemonError(Exception):
    pass


class DaemonClient:
    def __init__(self, socket_path: str, timeout: float = 5.0):
        self.socket_path = socket_path
        self.timeout = timeout

    def send(self, command: str) -> str:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(self.timeout)
            sock.connect(self.socket_path)
            sock.sendall((command + "\n").encode("utf-8"))
            chunks = []
            while True:
                data = sock.recv(4096)
                if not data:
                    break
                chunks.append(data)
                if b"\n" in data:
                    break
            return b"".join(chunks).decode("utf-8").strip()

    def status(self) -> dict:
        return _parse_kv(self.send("STATUS"))

    def connect(self, server_id: str | None = None) -> str:
        reply = self.send("CONNECT" if server_id is None else f"CONNECT {server_id}")
        _raise_if_fail(reply)
        return reply

    def disconnect(self) -> None:
        _raise_if_fail(self.send("DISCONNECT"))

    def login(self, username: str, password: str) -> None:
        _raise_if_fail(self.send(f"LOGIN {username} {password}"))

    def allow_lan(self, on: bool) -> None:
        _raise_if_fail(self.send("ALLOWLAN on" if on else "ALLOWLAN off"))

    def servers(self) -> list[tuple[str, str]]:
        reply = self.send("SERVERS")
        _raise_if_fail(reply)
        entries = []
        for item in reply.split(";"):
            if "=" in item:
                sid, title = item.split("=", 1)
                entries.append((sid.strip(), title.strip()))
        return entries


def _raise_if_fail(reply: str) -> None:
    if reply.startswith("FAIL"):
        raise DaemonError(reply)


def _parse_kv(line: str) -> dict:
    out = {}
    for item in line.split(" "):
        if "=" in item:
            key, value = item.split("=", 1)
            out[key] = value
    return out
