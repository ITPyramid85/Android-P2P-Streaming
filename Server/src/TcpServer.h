#pragma once

#include "ClientHandler.h"

#include <QAbstractSocket>
#include <QByteArray>
#include <QHash>
#include <QJsonObject>
#include <QMutex>
#include <QObject>
#include <QSet>
#include <QString>

class QTcpSocket;
class QTimer;

class TcpServer : public QObject
{
    Q_OBJECT

public:
    // Default (cloud) relay. The active endpoint can be overridden at startup by a
    // "relay.cfg" file next to the executable or the REMOTEAPP_RELAY env var, both
    // "host" or "host:port". Point it at a relay on the same PC / LAN to skip the
    // internet round-trip for a near-direct, low-latency link.
    static constexpr const char *kRelayHost = "167.114.172.44";
    static constexpr quint16 kRelayPort = 9000;

    explicit TcpServer(QObject *parent = nullptr);
    ~TcpServer() override;

    bool start(quint16 preferredPort = kRelayPort);
    void stop();
    bool isListening() const;
    quint16 port() const { return m_port; }

    ClientHandler *handlerForId(const QString &clientId) const;
    ClientHandler *attachAgent(const QString &agentId);
    bool isAgentOnline(const QString &agentId) const;

signals:
    void clientRegistered(const ClientInfo &info);
    void clientDisconnected(const QString &clientId);
    void clientMessage(const QString &clientId, const QString &message);
    void logMessage(const QString &message, bool highlight);

private slots:
    void onControlConnected();
    void onControlDisconnected();
    void onControlReadyRead();
    void onControlError(QAbstractSocket::SocketError error);
    void onReconnectTimer();
    void onHandlerRegistered(const ClientInfo &info);
    void onHandlerDisconnected(const QString &clientId);
    void onHandlerMessage(const QString &clientId, const QString &message);

private:
    QString formatTimestamp() const;
    void loadRelayConfig();
    void connectToRelay();
    void sendDesktopHello();
    void pumpControlPackets();
    void handleRelayJson(const QByteArray &payload);
    void applyPeerOnline(const QJsonObject &object);
    void applyPeerOffline(const QString &agentId);
    ClientHandler *adoptSocket(QTcpSocket *socket, const ClientInfo &identity);
    void closeHandler(ClientHandler *handler, bool emitOffline);
    void reapHandler(ClientHandler *handler);

    QTcpSocket *m_control = nullptr;
    QTimer *m_reconnectTimer = nullptr;
    QByteArray m_controlRx;
    QHash<QString, ClientInfo> m_agents;
    QHash<QString, ClientHandler *> m_handlers;
    QSet<QString> m_attaching;
    mutable QMutex m_sessionMutex;
    quint16 m_port = 0;
    bool m_wantConnected = false;
    QString m_relayHost = QString::fromLatin1(kRelayHost);
    quint16 m_relayPort = kRelayPort;
    bool m_relayLoaded = false;
    // Stable per-process id so the relay can tell this desktop's viewers apart
    // from other desktops (and drop a stale viewer when we reconnect).
    QString m_desktopId;
};
