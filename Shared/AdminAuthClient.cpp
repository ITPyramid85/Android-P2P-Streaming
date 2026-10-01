#include "AdminAuthClient.h"
#include "AuthProtocol.h"

#include <QAbstractSocket>
#include <QJsonObject>
#include <QTcpSocket>

AdminAuthClient::AdminAuthClient(QObject *parent)
    : QObject(parent)
{
    m_timeout.setSingleShot(true);
    m_timeout.setInterval(8000);
    connect(&m_timeout, &QTimer::timeout, this, &AdminAuthClient::onLoginTimeout);

    m_ping.setInterval(2000);
    connect(&m_ping, &QTimer::timeout, this, &AdminAuthClient::sendPing);
}

void AdminAuthClient::login(const QString &host, quint16 port, const QString &username,
                            const QString &password)
{
    resetSocket();
    m_pendingUser = username.trimmed();
    m_pendingPass = password;
    m_username.clear();
    m_loginInProgress = true;
    m_authenticated = false;

    if (host.trimmed().isEmpty() || port == 0) {
        m_loginInProgress = false;
        emit failed(QStringLiteral("Enter AdminPanel IP address and port."));
        return;
    }
    if (m_pendingUser.isEmpty() || m_pendingPass.isEmpty()) {
        m_loginInProgress = false;
        emit failed(QStringLiteral("Enter username and password."));
        return;
    }

    m_socket = new QTcpSocket(this);
    connect(m_socket, &QTcpSocket::connected, this, &AdminAuthClient::onConnected);
    connect(m_socket, &QTcpSocket::readyRead, this, &AdminAuthClient::onReadyRead);
    connect(m_socket, &QTcpSocket::disconnected, this, &AdminAuthClient::onDisconnected);
    connect(m_socket, &QAbstractSocket::errorOccurred, this, &AdminAuthClient::onSocketError);

    m_timeout.start();
    m_socket->connectToHost(host.trimmed(), port);
}

void AdminAuthClient::disconnectFromAdmin()
{
    m_authenticated = false;
    m_loginInProgress = false;
    m_username.clear();
    resetSocket();
}

void AdminAuthClient::resetSocket()
{
    m_timeout.stop();
    m_ping.stop();
    m_buffer.clear();
    if (!m_socket) {
        return;
    }
    QTcpSocket *socket = m_socket;
    m_socket = nullptr;
    socket->disconnect(this);
    socket->abort();
    socket->deleteLater();
}

void AdminAuthClient::onConnected()
{
    if (!m_socket || !m_loginInProgress) {
        return;
    }
    m_socket->write(AuthProtocol::loginRequest(m_pendingUser, m_pendingPass));
    m_socket->flush();
    m_pendingPass.clear();
}

void AdminAuthClient::onReadyRead()
{
    if (!m_socket) {
        return;
    }
    m_buffer += m_socket->readAll();
    while (true) {
        const int newline = m_buffer.indexOf('\n');
        if (newline < 0) {
            break;
        }
        const QByteArray line = m_buffer.left(newline).trimmed();
        m_buffer.remove(0, newline + 1);
        if (!line.isEmpty()) {
            handleLine(line);
        }
    }
}

void AdminAuthClient::handleLine(const QByteArray &line)
{
    const QJsonObject obj = AuthProtocol::parseLine(line);
    const QString type = obj.value(QStringLiteral("type")).toString();
    if (type == QLatin1String("ok")) {
        if (m_loginInProgress) {
            m_timeout.stop();
            m_loginInProgress = false;
            m_authenticated = true;
            m_username = m_pendingUser;
            m_pendingUser.clear();
            m_ping.start();
            emit succeeded(m_username);
        }
        return;
    }
    if (type == QLatin1String("error")) {
        const QString message = obj.value(QStringLiteral("message")).toString(
            QStringLiteral("Incorrect username or password, or the account was removed."));
        m_timeout.stop();
        m_loginInProgress = false;
        m_authenticated = false;
        resetSocket();
        emit failed(message);
        return;
    }
    if (type == QLatin1String("revoke")) {
        const bool hadSession = m_authenticated || m_loginInProgress;
        m_timeout.stop();
        m_loginInProgress = false;
        m_authenticated = false;
        resetSocket();
        if (hadSession) {
            emit sessionEnded(QStringLiteral(
                "This RemoteController user was removed in AdminPanel.\n"
                "Listening and mobile sockets have been stopped."));
        }
    }
}

void AdminAuthClient::onSocketError()
{
    if (!m_loginInProgress) {
        return;
    }
    m_timeout.stop();
    m_loginInProgress = false;
    const QString message = m_socket
        ? m_socket->errorString()
        : QStringLiteral("Cannot reach AdminPanel.");
    resetSocket();
    emit failed(QStringLiteral("Cannot reach AdminPanel (%1).").arg(message));
}

void AdminAuthClient::onDisconnected()
{
    m_timeout.stop();
    m_ping.stop();
    if (m_loginInProgress) {
        m_loginInProgress = false;
        resetSocket();
        emit failed(QStringLiteral("Cannot reach AdminPanel."));
        return;
    }
    if (m_authenticated) {
        m_authenticated = false;
        resetSocket();
        emit sessionEnded(QStringLiteral(
            "Lost connection to AdminPanel.\n"
            "Listening and mobile sockets have been stopped."));
    }
}

void AdminAuthClient::onLoginTimeout()
{
    if (!m_loginInProgress) {
        return;
    }
    m_loginInProgress = false;
    resetSocket();
    emit failed(QStringLiteral("AdminPanel did not respond. Check IP address and port."));
}

void AdminAuthClient::sendPing()
{
    if (!m_socket || !m_authenticated) {
        return;
    }
    m_socket->write(AuthProtocol::pingRequest());
}
