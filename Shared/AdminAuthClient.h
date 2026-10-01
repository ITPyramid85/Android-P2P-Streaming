#pragma once

#include <QObject>
#include <QString>
#include <QTimer>

class QTcpSocket;

class AdminAuthClient : public QObject
{
    Q_OBJECT

public:
    explicit AdminAuthClient(QObject *parent = nullptr);

    void login(const QString &host, quint16 port, const QString &username, const QString &password);
    void disconnectFromAdmin();
    bool isAuthenticated() const { return m_authenticated; }
    QString username() const { return m_username; }

signals:
    void succeeded(const QString &username);
    void failed(const QString &message);
    void sessionEnded(const QString &reason);

private:
    void resetSocket();
    void onConnected();
    void onReadyRead();
    void onSocketError();
    void onDisconnected();
    void onLoginTimeout();
    void sendPing();
    void handleLine(const QByteArray &line);

    QTcpSocket *m_socket = nullptr;
    QByteArray m_buffer;
    QString m_pendingUser;
    QString m_pendingPass;
    QString m_username;
    bool m_loginInProgress = false;
    bool m_authenticated = false;
    QTimer m_timeout;
    QTimer m_ping;
};
