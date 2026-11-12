"""Qt6/QML front end for aipd. Qt imports live here only; daemon_client
stays stdlib so the protocol is testable without Qt."""

import os
import sys

SOCKET = os.environ.get("AIPD_SOCKET", "/run/aipd/aipd.sock")

try:
    from PySide6.QtCore import QObject, Property, Signal, Slot
    from PySide6.QtGui import QIcon
    from PySide6.QtQml import QQmlApplicationEngine
    from PySide6.QtWidgets import QApplication, QSystemTrayIcon, QMenu
    HAS_QT = True
except ImportError:
    HAS_QT = False

from daemon_client import DaemonClient, DaemonError


if HAS_QT:
    class Backend(QObject):
        changed = Signal()

        def __init__(self, client):
            super().__init__()
            self._client = client
            self._status = {"status": "Off", "server": "-", "error": "-"}
            self._servers = []
            self._allow_lan = False

        @Property(str, notify=changed)
        def daemonStatus(self):
            return {"UP": "On", "CONNECTING": "Connecting…",
                    "PARKED": "Connecting…"}.get(self._status.get("status"), "Off")

        @Property(str, notify=changed)
        def activeServer(self):
            return self._status.get("server", "-")

        @Property(str, notify=changed)
        def activeServerId(self):
            return self._status.get("server", "-")

        @Property(str, notify=changed)
        def lastError(self):
            return self._status.get("error", "-")

        @Property(bool, notify=changed)
        def allowLan(self):
            return self._allow_lan

        @Property("QVariantList", notify=changed)
        def serverList(self):
            return self._servers

        @Slot()
        def refresh(self):
            try:
                self._status = self._client.status()
                raw = self._client.servers()
                self._servers = [{"id": sid, "title": title} for sid, title in raw]
            except DaemonError as exc:
                self._status = {"status": "Off", "server": "-", "error": str(exc)}
            self.changed.emit()

        @Slot()
        def toggleConnect(self):
            try:
                if self._status.get("status") == "UP":
                    self._client.disconnect()
                else:
                    self._client.connect()
            except DaemonError as exc:
                self._status = dict(self._status, error=str(exc))
            self.refresh()

        @Slot(str)
        def pickServer(self, server_id):
            try:
                self._client.connect(server_id)
            except DaemonError as exc:
                self._status = dict(self._status, error=str(exc))
            self.refresh()

        @Slot(bool)
        def setAllowLan(self, on):
            try:
                self._client.allow_lan(on)
                self._allow_lan = on
            except DaemonError as exc:
                self._status = dict(self._status, error=str(exc))
            self.changed.emit()

        @Slot(str, str)
        def login(self, username, password):
            try:
                self._client.login(username, password)
                self._status = dict(self._status, error="-")
            except DaemonError as exc:
                self._status = dict(self._status, error=str(exc))
            self.changed.emit()


def main():
    if not HAS_QT:
        print("PySide6 is not installed; install the aip-gui package.", file=sys.stderr)
        return 2
    app = QApplication(sys.argv)
    client = DaemonClient(SOCKET)
    backend = Backend(client)
    backend.refresh()
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("backend", backend)
    engine.load(os.path.join(os.path.dirname(__file__), "qml", "Main.qml"))
    if not engine.rootObjects():
        return 1
    tray = QSystemTrayIcon(app)
    menu = QMenu()
    toggle = menu.addAction("Connect")
    toggle.triggered.connect(backend.toggleConnect)
    menu.addAction("Quit", app.quit)
    tray.setContextMenu(menu)
    tray.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
