
#ifndef SETTINGS_H
#define SETTINGS_H

#include <QObject>
#include <QSettings>

class Settings : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString macAddress READ macAddress WRITE setMacAddress NOTIFY macAddressChanged)

public:
    explicit Settings(QObject *parent = nullptr);

    QString macAddress() const;
    void setMacAddress(const QString &macAddress);

signals:
    void macAddressChanged();

private:
    QSettings m_settings;
};

#endif // SETTINGS_H
