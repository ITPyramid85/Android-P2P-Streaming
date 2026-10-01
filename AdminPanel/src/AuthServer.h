#pragma once

#include <QHash>
#include <QObject>
#include <QString>
#include <QTcpServer>

class QTcpSocket;

class AuthServer : public QObject
{
    Q_OBJECT

public:
    explicit AuthServer(QObject *parent = nullptr);

    bool start(quint16 port);
    quint16 port() const { return m_server.serverPort(); }
    QString lastError() const { return m_lastError; }
    void revokeUser(const QString &username);

private:
    struct Connection {
        QByteArray buffer;
        QString username;
    };

    void onNewConnection();
    void onReadyRead();
    void onDisconnected();
    void handleLine(QTcpSocket *socket, const QByteArray &line);
    void sendLine(QTcpSocket *socket, const QByteArray &line);
    void closeSocket(QTcpSocket *socket);

    QTcpServer m_server;
    QHash<QTcpSocket *, Connection> m_connections;
    QString m_lastError;
};
