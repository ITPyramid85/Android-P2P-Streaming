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
    // Close this controller's media socket for a mobile without marking it offline.
    // Used when handing the mobile back so the next controller starts clean.
    void detachAgent(const QString &agentId);
    bool isAgentOnline(const QString &agentId) const;
    // Ask the relay for control of this mobile. The relay decides, because only it
    // sees every controller; the answer arrives as agentClaimed or agentClaimDenied.
    // Nothing may be opened or attached before the grant.
    void requestAgentClaim(const QString &agentId);
    // Hand the mobile back on purpose. [endShare] stops sharing and remote control
    // on the phone and leaves the app running, the same as its own Stop button.
    // Without it the phone stays on offer for the next controller.
    void releaseAgentClaim(const QString &agentId, bool endShare = false);
    // Controller Stop. The relay is told by releaseAgentClaim; this drops the row
    // and the media socket in the same step, and ignores a later list update for
    // the session that just ended.
    void dropLocalEnded(const QString &agentId);
    // The local hour elapsed. The relay ends the offer for every controller.
    void expireAgentSession(const QString &agentId);

signals:
    void clientRegistered(const ClientInfo &info);
    void clientDisconnected(const QString &clientId);
    void clientBusy(const QString &clientId);
    // The relay granted control of this mobile, with the time left in the session.
    void agentClaimed(const QString &agentId, qint64 remainMs, const QString &controlKey);
    // Refused: another controller holds it, or the sharing offer is already spent.
    void agentClaimDenied(const QString &agentId, const QString &reason);
    // Sharing stopped by the user, or the one-hour limit elapsed. The row goes away.
    void clientRemoved(const QString &clientId);
    void clientMessage(const QString &clientId, const QString &message);
    void logMessage(const QString &message, bool highlight);

private slots:
    void onControlConnected();
    void onControlDisconnected();
    void onControlReadyRead();
    void onControlError(QAbstractSocket::SocketError error);
    void onReconnectTimer();
    void onHeartbeatTimer();
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
    void applyPeerOffline(const QString &agentId, bool busy, bool ended, quint32 sessionId);
    // Drops a list update that belongs to an older phone socket than the one
    // already shown. Session ids only grow, so a delayed goodbye cannot erase
    // a phone that has already started sharing again.
    bool noteListEpoch(const QString &agentId, quint32 sessionId);
    void writeClaim(const QString &agentId, bool claimed, bool endShare = false);
    ClientHandler *adoptSocket(QTcpSocket *socket, const ClientInfo &identity);
    void closeHandler(ClientHandler *handler, bool emitOffline);
    void reapHandler(ClientHandler *handler);

    QTcpSocket *m_control = nullptr;
    QTimer *m_reconnectTimer = nullptr;
    // Proof of life for the relay. A mobile this controller holds cannot be given
    // to anyone else until the relay notices we are gone, so silence is expensive.
    QTimer *m_heartbeatTimer = nullptr;
    QByteArray m_controlRx;
    QHash<QString, ClientInfo> m_agents;
    QHash<QString, quint32> m_listEpoch;
    // Session ids that Stop already removed. A peer_online at or below this id is
    // the share that just ended coming back, not a new Start.
    QHash<QString, quint32> m_endedEpoch;
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
    // Mobiles whose preview is open here. Re-sent after the control socket reconnects.
    QSet<QString> m_claimed;
    // Per-mobile key proving this controller currently owns it. Stamped onto every
    // input event so the phone can tell our commands from a stale controller's.
    QHash<QString, QString> m_controlKeys;
};
