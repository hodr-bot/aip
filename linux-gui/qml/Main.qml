import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

// AIP VPN Linux client. Dark #101410, surface #1A211A, accent #7ED321.
// Backend is a Python object exposed as `backend` with:
//   properties: daemonStatus, activeServer, lastError, serverList, allowLan
//   slots: refresh(), toggleConnect(), pickServer(id), setAllowLan(on),
//          login(user, pass), suspicious label strings come from the daemon.

ApplicationWindow {
    id: root
    visible: true
    width: 380
    height: 620
    title: "AIP VPN"
    color: "#101410"

    property string pill: backend ? backend.daemonStatus : "Off"

    header: ToolBar {
        background: Rectangle { color: "#1A211A" }
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 16
            anchors.rightMargin: 16
            Label {
                text: "AIP VPN"
                color: "#7ED321"
                font.bold: true
                font.pointSize: 14
                Layout.fillWidth: true
            }
            Rectangle {
                radius: 10
                color: pill === "On" ? "#1E3A1E" : (pill === "Connecting…" ? "#3A321E" : "#2A2A2A")
                width: pillLabel.width + 20
                height: 22
                Label {
                    id: pillLabel
                    anchors.centerIn: parent
                    text: pill
                    color: pill === "On" ? "#7ED321" : "#9DB09A"
                    font.pointSize: 9
                }
            }
        }
    }

    footer: ToolBar {
        background: Rectangle { color: "#1A211A" }
        RowLayout {
            anchors.fill: parent
            Button { text: "Refresh"; flat: true; onClicked: backend.refresh() }
            Button { text: "Account"; flat: true; onClicked: tabBar.currentIndex = 3 }
            Item { Layout.fillWidth: true }
            Label { text: "aipd"; color: "#9DB09A"; font.pointSize: 8 }
        }
    }

    TabBar {
        id: tabBar
        anchors.bottom: parent.bottom
    }

    StackLayout {
        anchors.fill: parent
        anchors.margins: 16
        currentIndex: tabBar.currentIndex

        // ---- Status ----
        ColumnLayout {
            spacing: 12
            Button {
                text: pill === "On" ? "Disconnect" : "Connect"
                font.pointSize: 16
                Layout.preferredWidth: 200
                Layout.preferredHeight: 200
                Layout.alignment: Qt.AlignHCenter
                background: Rectangle {
                    radius: 100
                    color: "#1A211A"
                    border.color: "#7ED321"
                    border.width: 2
                }
                contentItem: Label {
                    text: parent.text
                    color: "#7ED321"
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                onClicked: backend.toggleConnect()
            }
            Label {
                text: backend ? backend.lastError : ""
                color: "#E57373"
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
                visible: text !== "" && text !== "-"
            }
            GroupBox {
                title: "Server"
                Layout.fillWidth: true
                background: Rectangle { color: "#1A211A"; radius: 8 }
                label: Label { text: parent.title; color: "#9DB09A" }
                RowLayout {
                    width: parent.width
                    Label {
                        text: backend && backend.activeServer !== "-" ? backend.activeServer : "Tap Change to pick a server"
                        color: "#E8F0E4"
                        Layout.fillWidth: true
                    }
                    Button { text: "Change"; onClicked: tabBar.currentIndex = 1 }
                }
            }
            GroupBox {
                title: "Local network"
                Layout.fillWidth: true
                background: Rectangle { color: "#1A211A"; radius: 8 }
                label: Label { text: parent.title; color: "#9DB09A" }
                RowLayout {
                    width: parent.width
                    Label { text: "Allow local network"; color: "#E8F0E4"; Layout.fillWidth: true }
                    Switch {
                        checked: backend ? backend.allowLan : false
                        onToggled: backend.setAllowLan(checked)
                    }
                }
            }
        }

        // ---- Servers ----
        ColumnLayout {
            spacing: 8
            TextField {
                id: search
                placeholderText: "Search servers"
                Layout.fillWidth: true
            }
            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                model: backend ? backend.serverList : []
                delegate: ItemDelegate {
                    width: ListView.view.width
                    highlighted: modelData.id === backend.activeServerId
                    contentItem: RowLayout {
                        Rectangle {
                            width: 40; height: 28; radius: 4
                            color: modelData.id === backend.activeServerId ? "#7ED321" : "#2A332A"
                            Label {
                                anchors.centerIn: parent
                                text: modelData.id.substring(0, 2).toUpperCase()
                                color: "#101410"
                                font.bold: true
                            }
                        }
                        Label {
                            text: modelData.title + (modelData.latency ? "  " + modelData.latency + "ms" : "")
                            color: "#E8F0E4"
                            Layout.fillWidth: true
                        }
                    }
                    onClicked: backend.pickServer(modelData.id)
                }
            }
        }

        // ---- Kill switch ----
        ColumnLayout {
            spacing: 12
            GroupBox {
                title: "Kill switch"
                Layout.fillWidth: true
                background: Rectangle { color: "#1A211A"; radius: 8 }
                label: Label { text: parent.title; color: "#9DB09A" }
                ColumnLayout {
                    width: parent.width
                    RowLayout {
                        Layout.fillWidth: true
                        Label {
                            text: backend && backend.daemonStatus !== "Off" ? "On" : "Off"
                            color: "#E8F0E4"
                            Layout.fillWidth: true
                        }
                        Switch { checked: backend ? backend.daemonStatus !== "Off" : false; enabled: false }
                    }
                    Label {
                        text: "Kill switch is off: if the tunnel drops, traffic goes out direct. No warning is shown elsewhere."
                        color: "#9DB09A"
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                        visible: backend ? backend.daemonStatus === "Off" : true
                    }
                }
            }
        }

        // ---- Account ----
        ColumnLayout {
            spacing: 8
            TextField { id: username; placeholderText: "Username"; Layout.fillWidth: true }
            TextField { id: password; placeholderText: "Password"; echoMode: TextInput.Password; Layout.fillWidth: true }
            Button {
                text: "Log in"
                Layout.fillWidth: true
                onClicked: backend.login(username.text, password.text)
            }
        }
    }
}
