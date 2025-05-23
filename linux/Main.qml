pragma ComponentBehavior: Bound

import QtQuick 2.15
import QtQuick.Controls 2.15

ApplicationWindow {
    id: mainWindow
    visible: !airPodsTrayApp.hideOnStart
    width: 400
    height: 300
    title: "AirPods Settings"
    objectName: "mainWindowObject"

    onClosing: mainWindow.visible = false

    function reopen(pageToLoad) {
        if (pageToLoad == "settings")
        {
            if (stackView.depth == 1)
            {
                stackView.push(settingsPage)
            }
        }
        else
        {
            if (stackView.depth > 1)
            {
                stackView.pop()
            }
        }

        if (!mainWindow.visible) {
            mainWindow.visible = true
        }
        raise()
        requestActivate()
    }

    // Mouse area for handling back/forward navigation
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.BackButton | Qt.ForwardButton
        onClicked: (mouse) => {
            if (mouse.button === Qt.BackButton && stackView.depth > 1) {
                stackView.pop()
            } else if (mouse.button === Qt.ForwardButton) {
                console.log("Forward button pressed")
            }
        }
    }

    StackView {
        id: stackView
        anchors.fill: parent
        initialItem: mainPage
    }

    FontLoader {
        id: iconFont
        source: "qrc:/icons/assets/fonts/SF-Symbols-6.ttf"
    }

    Component {
        id: mainPage
        Item {
            Column {
                anchors.fill: parent
                spacing: 20
                padding: 20

                // Connection status indicator (Apple-like pill shape)
                Rectangle {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.topMargin: 10
                    width: 120
                    height: 24
                    radius: 12
                    color: airPodsTrayApp.airpodsConnected ? "#30D158" : "#FF453A"
                    opacity: 0.8
                    visible: !airPodsTrayApp.airpodsConnected

                    Label {
                        anchors.centerIn: parent
                        text: airPodsTrayApp.airpodsConnected ? "Connected" : "Disconnected"
                        color: "white"
                        font.pixelSize: 12
                        font.weight: Font.Medium
                    }
                }

                // Battery Indicator Row
                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: 8

                    PodColumn {
                        isVisible: airPodsTrayApp.battery.leftPodAvailable
                        inEar: airPodsTrayApp.leftPodInEar
                        iconSource: "qrc:/icons/assets/" + airPodsTrayApp.podIcon
                        batteryLevel: airPodsTrayApp.battery.leftPodLevel
                        isCharging: airPodsTrayApp.battery.leftPodCharging
                        indicator: "L"
                    }

                    PodColumn {
                        isVisible: airPodsTrayApp.battery.rightPodAvailable
                        inEar: airPodsTrayApp.rightPodInEar
                        iconSource: "qrc:/icons/assets/" + airPodsTrayApp.podIcon
                        batteryLevel: airPodsTrayApp.battery.rightPodLevel
                        isCharging: airPodsTrayApp.battery.rightPodCharging
                        indicator: "R"
                    }

                    PodColumn {
                        isVisible: airPodsTrayApp.battery.caseAvailable
                        inEar: true
                        iconSource: "qrc:/icons/assets/" + airPodsTrayApp.caseIcon
                        batteryLevel: airPodsTrayApp.battery.caseLevel
                        isCharging: airPodsTrayApp.battery.caseCharging
                    }
                }

                SegmentedControl {
                    anchors.horizontalCenter: parent.horizontalCenter
                    model: ["Off", "Noise Cancellation", "Transparency", "Adaptive"]
                    currentIndex: airPodsTrayApp.noiseControlMode
                    onCurrentIndexChanged: airPodsTrayApp.noiseControlMode = currentIndex
                    visible: airPodsTrayApp.airpodsConnected
                }

                Slider {
                    visible: airPodsTrayApp.adaptiveModeActive
                    from: 0
                    to: 100
                    stepSize: 1
                    value: airPodsTrayApp.adaptiveNoiseLevel

                    Timer {
                        id: debounceTimer
                        interval: 500
                        onTriggered: if (!parent.pressed) airPodsTrayApp.setAdaptiveNoiseLevel(parent.value)
                    }

                    onPressedChanged: if (!pressed) airPodsTrayApp.setAdaptiveNoiseLevel(value)
                    onValueChanged: if (pressed) debounceTimer.restart()

                    Label {
                        text: "Adaptive Noise Level: " + parent.value
                        anchors.top: parent.bottom
                    }
                }

                Switch {
                    visible: airPodsTrayApp.airpodsConnected
                    text: "Conversational Awareness"
                    checked: airPodsTrayApp.conversationalAwareness
                    onCheckedChanged: airPodsTrayApp.conversationalAwareness = checked
                }
            }

            RoundButton {
                anchors.top: parent.top
                anchors.right: parent.right
                anchors.margins: 10
                font.family: iconFont.name
                font.pixelSize: 18
                text: "\uf958" // U+F958
                onClicked: stackView.push(settingsPage)
            }
        }
    }

    Component {
        id: settingsPage
        Page {
            id: settingsPageItem
            title: "Settings"

            ScrollView {
                anchors.fill: parent

                Column {
                    width: parent.width
                    spacing: 20
                    padding: 20

                    Label {
                        text: "Settings"
                        font.pixelSize: 24
                        // center the label
                        anchors.horizontalCenter: parent.horizontalCenter
                    }

                    Column {
                        spacing: 5 // Small gap between label and ComboBox

                        Label {
                            text: "Pause Behavior When Removing AirPods:"
                        }

                        ComboBox {
                            width: parent.width // Ensures full width
                            model: ["One Removed", "Both Removed", "Never"]
                            currentIndex: airPodsTrayApp.earDetectionBehavior
                            onActivated: airPodsTrayApp.earDetectionBehavior = currentIndex
                        }
                    }

                    Switch {
                        text: "Cross-Device Connectivity with Android"
                        checked: airPodsTrayApp.crossDeviceEnabled
                        onCheckedChanged: {
                            airPodsTrayApp.setCrossDeviceEnabled(checked)
                        }
                    }

                    Switch {
                        text: "Auto-Start on Login"
                        checked: airPodsTrayApp.autoStartManager.autoStartEnabled
                        onCheckedChanged: airPodsTrayApp.autoStartManager.autoStartEnabled = checked
                    }

                    Switch {
                        text: "Enable System Notifications"
                        checked: airPodsTrayApp.notificationsEnabled
                        onCheckedChanged: airPodsTrayApp.notificationsEnabled = checked
                    }

                    Row {
                        spacing: 5
                        Label {
                            text: "Bluetooth Retry Attempts:"
                            anchors.verticalCenter: parent.verticalCenter
                        }
                        SpinBox {
                            from: 1
                            to: 10
                            value: airPodsTrayApp.retryAttempts
                            onValueChanged: airPodsTrayApp.retryAttempts = value
                        }
                    }

                    Row {
                        spacing: 10
                        visible: airPodsTrayApp.airpodsConnected

                        TextField {
                            id: newNameField
                            placeholderText: airPodsTrayApp.deviceName
                            maximumLength: 32
                        }

                        Button {
                            text: "Rename"
                            onClicked: airPodsTrayApp.renameAirPods(newNameField.text)
                        }
                    }
                }
            }

            // Floating back button
            RoundButton {
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.margins: 10
                font.family: iconFont.name
                font.pixelSize: 18
                text: "\uecb1" // U+ECB1
                onClicked: stackView.pop()
            }
        }
    }
}