
import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Page {
    title: "Settings"

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 10

        Label {
            text: "Phone Bluetooth MAC Address"
        }

        TextField {
            id: macAddressField
            placeholderText: "XX:XX:XX:XX:XX:XX"
            text: settings.macAddress
            onEditingFinished: {
                settings.macAddress = text
            }
        }
    }
}
