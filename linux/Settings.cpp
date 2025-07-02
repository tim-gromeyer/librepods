
#include "Settings.h"

Settings::Settings(QObject *parent)
    : QObject(parent)
{
}

QString Settings::macAddress() const
{
    return m_settings.value("macAddress").toString();
}

void Settings::setMacAddress(const QString &macAddress)
{
    m_settings.setValue("macAddress", macAddress);
    emit macAddressChanged();
}
