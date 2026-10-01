#include "AuthServer.h"
#include "AuthProtocol.h"
#include "UserDatabase.h"

#include <QHostAddress>
#include <QJsonObject>
#include <QTcpSocket>

AuthServer::AuthServer(QObject *parent)
    : QObject(parent)
{
    connect(&m_server, &QTcpServer::newConnection, this, &AuthServer::onNewConnection);
}

bool AuthServer::start(quint16 port)
{
    if (m_server.isListening()) {
        m_server.close();
    }
    if (!m_server.listen(QHostAddress::Any, port)) {
        m_lastError = m_server.errorString();
        return false;
    }
    m_lastError.clear();
    return true;
}

void AuthServer::revokeUser(const QString &username)
{
    const QString key = username.trimmed().toLower();
    const auto sockets = m_connections.keys();
    for (QTcpSocket *socket : sockets) {
        if (m_connections.value(socket).username.toLower() != key) {
            continue;
        }
        sendLine(socket, AuthProtocol::revokeReply());
        closeSocket(socket);
    }
}

void AuthServer::onNewConnection()
{
    while (m_server.hasPendingConnections()) {
        QTcpSocket *socket = m_server.nextPendingConnection();
        socket->setParent(this);
        m_connections.insert(socket, {});
        connect(socket, &QTcpSocket::readyRead, this, &AuthServer::onReadyRead);
        connect(socket, &QTcpSocket::disconnected, this, &AuthServer::onDisconnected);
    }
}

void AuthServer::onReadyRead()
{
    auto *socket = qobject_cast<QTcpSocket *>(sender());
    if (!socket || !m_connections.contains(socket)) {
        return;
    }
    Connection &conn = m_connections[socket];
    conn.buffer += socket->readAll();
    while (true) {
        const int newline = conn.buffer.indexOf('\n');
        if (newline < 0) {
            break;
        }
        const QByteArray line = conn.buffer.left(newline).trimmed();
        conn.buffer.remove(0, newline + 1);
        if (!line.isEmpty()) {
            handleLine(socket, line);
            if (!m_connections.contains(socket)) {
                return;
            }
        }
    }
}

void AuthServer::handleLine(QTcpSocket *socket, const QByteArray &line)
{
    const QJsonObject obj = AuthProtocol::parseLine(line);
    const QString type = obj.value(QStringLiteral("type")).toString();
    Connection &conn = m_connections[socket];

    if (type == QLatin1String("login")) {
        const QString username = obj.value(QStringLiteral("username")).toString();
        const QString password = obj.value(QStringLiteral("password")).toString();
        if (UserDatabase::instance().authenticate(username, password)) {
            conn.username = username.trimmed();
            sendLine(socket, AuthProtocol::okReply());
            return;
        }
        sendLine(socket, AuthProtocol::errorReply(
            QStringLiteral("Incorrect username or password, or the account was removed.")));
        closeSocket(socket);
        return;
    }

    if (type == QLatin1String("ping")) {
        if (conn.username.isEmpty() || !UserDatabase::instance().userExists(conn.username)) {
            sendLine(socket, AuthProtocol::revokeReply());
            closeSocket(socket);
            return;
        }
        sendLine(socket, AuthProtocol::okReply());
        return;
    }

    sendLine(socket, AuthProtocol::errorReply(QStringLiteral("Unknown request.")));
    closeSocket(socket);
}

void AuthServer::onDisconnected()
{
    auto *socket = qobject_cast<QTcpSocket *>(sender());
    if (!socket) {
        return;
    }
    m_connections.remove(socket);
    socket->deleteLater();
}

void AuthServer::sendLine(QTcpSocket *socket, const QByteArray &line)
{
    if (!socket) {
        return;
    }
    socket->write(line);
    socket->flush();
}

void AuthServer::closeSocket(QTcpSocket *socket)
{
    if (!socket) {
        return;
    }
    m_connections.remove(socket);
    socket->disconnect(this);
    socket->disconnectFromHost();
    socket->deleteLater();
}
