#include "TcpServer.h"
#include "Packet.h"

#include <QAbstractSocket>
#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QHostAddress>
#include <QJsonDocument>
#include <QJsonObject>
#include <QList>
#include <QMetaObject>
#include <QMutexLocker>
#include <QProcessEnvironment>
#include <QRegularExpression>
#include <QTcpSocket>
#include <QThread>
#include <QTimer>
#include <QSysInfo>
#include <QUuid>
#include <QtEndian>

TcpServer::TcpServer(QObject *parent)
    : QObject(parent)
    , m_control(new QTcpSocket(this))
    , m_reconnectTimer(new QTimer(this))
{
    m_desktopId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_control->setSocketOption(QAbstractSocket::LowDelayOption, 1);
    m_control->setSocketOption(QAbstractSocket::KeepAliveOption, 1);
    m_control->setReadBufferSize(64 * 1024);
    connect(m_control, &QTcpSocket::connected, this, &TcpServer::onControlConnected);
    connect(m_control, &QTcpSocket::disconnected, this, &TcpServer::onControlDisconnected);
    connect(m_control, &QTcpSocket::readyRead, this, &TcpServer::onControlReadyRead);
    connect(m_control,
            &QAbstractSocket::errorOccurred,
            this,
            &TcpServer::onControlError);
    m_reconnectTimer->setSingleShot(true);
    connect(m_reconnectTimer, &QTimer::timeout, this, &TcpServer::onReconnectTimer);
    m_heartbeatTimer = new QTimer(this);
    m_heartbeatTimer->setInterval(4000);
    connect(m_heartbeatTimer, &QTimer::timeout, this, &TcpServer::onHeartbeatTimer);
}

void TcpServer::onHeartbeatTimer()
{
    if (!m_control || m_control->state() != QAbstractSocket::ConnectedState) {
        return;
    }
    QJsonObject ping;
    ping.insert(QStringLiteral("type"), QStringLiteral("ping"));
    m_control->write(Packet::encodeControlJson(ping));
    m_control->flush();
}

TcpServer::~TcpServer()
{
    stop();
}

bool TcpServer::start(quint16)
{
    loadRelayConfig();
    m_wantConnected = true;
    m_port = m_relayPort;
    if (m_control->state() == QAbstractSocket::ConnectedState) {
        return true;
    }
    connectToRelay();
    return true;
}

// Resolve the relay endpoint once: REMOTEAPP_RELAY env var wins, else a "relay.cfg"
// file next to the executable, else the built-in cloud default. Accepts "host" or
// "host:port". Editing the file lets a user run the relay locally / on the LAN and
// keep the traffic off the public internet without a rebuild.
void TcpServer::loadRelayConfig()
{
    if (m_relayLoaded) {
        return;
    }
    m_relayLoaded = true;

    QString raw = QProcessEnvironment::systemEnvironment()
                      .value(QStringLiteral("REMOTEAPP_RELAY"))
                      .trimmed();
    if (raw.isEmpty()) {
        QFile cfg(QDir(QCoreApplication::applicationDirPath())
                      .filePath(QStringLiteral("relay.cfg")));
        if (cfg.exists() && cfg.open(QIODevice::ReadOnly | QIODevice::Text)) {
            raw = QString::fromUtf8(cfg.readAll()).trimmed();
            cfg.close();
            // First non-empty line only, so comments on later lines are tolerated.
            raw = raw.split(QRegularExpression(QStringLiteral("[\\r\\n]")),
                            Qt::SkipEmptyParts)
                      .value(0)
                      .trimmed();
        }
    }
    if (raw.isEmpty()) {
        return;
    }

    QString host = raw;
    quint16 relayPort = kRelayPort;
    const int colon = raw.lastIndexOf(QChar(':'));
    if (colon > 0 && colon < raw.size() - 1) {
        bool ok = false;
        const uint parsed = raw.mid(colon + 1).trimmed().toUInt(&ok);
        if (ok && parsed > 0 && parsed <= 65535) {
            host = raw.left(colon).trimmed();
            relayPort = quint16(parsed);
        }
    }
    if (!host.isEmpty()) {
        m_relayHost = host;
        m_relayPort = relayPort;
    }
}

void TcpServer::stop()
{
    m_wantConnected = false;
    m_reconnectTimer->stop();

    QList<ClientHandler *> handlers;
    QStringList agentIds;
    {
        QMutexLocker lock(&m_sessionMutex);
        handlers = m_handlers.values();
        m_handlers.clear();
        agentIds = m_agents.keys();
        m_agents.clear();
        m_attaching.clear();
    }
    for (ClientHandler *handler : handlers) {
        closeHandler(handler, false);
    }
    for (const QString &id : agentIds) {
        emit clientDisconnected(id);
    }

    m_controlRx.clear();
    if (m_control->state() != QAbstractSocket::UnconnectedState) {
        m_control->abort();
    }
    m_port = 0;
}

bool TcpServer::isListening() const
{
    return m_control && m_control->state() == QAbstractSocket::ConnectedState;
}

ClientHandler *TcpServer::handlerForId(const QString &clientId) const
{
    QMutexLocker lock(&m_sessionMutex);
    return m_handlers.value(clientId, nullptr);
}

bool TcpServer::isAgentOnline(const QString &agentId) const
{
    QMutexLocker lock(&m_sessionMutex);
    return m_agents.contains(agentId);
}

void TcpServer::requestAgentClaim(const QString &agentId)
{
    const QString id = agentId.trimmed();
    if (id.isEmpty()) {
        return;
    }
    if (!m_control || m_control->state() != QAbstractSocket::ConnectedState) {
        emit agentClaimDenied(id, QStringLiteral("offline"));
        return;
    }
    writeClaim(id, true);
}

void TcpServer::releaseAgentClaim(const QString &agentId, bool endShare)
{
    const QString id = agentId.trimmed();
    if (id.isEmpty()) {
        return;
    }
    m_claimed.remove(id);
    m_controlKeys.remove(id);
    // Tell the relay first. It is the only party that can revoke this controller
    // for everyone at once, and it stops the phone's capture and its input the
    // moment it reads this line. Nothing below may be allowed to delay that:
    // detaching first made the phone's teardown wait on our socket cleanup.
    writeClaim(id, false, endShare);
    if (ClientHandler *handler = handlerForId(id)) {
        // The stop this window just queued is worth a few milliseconds — it is the
        // fastest path to the phone, going out over the direct UDP punch. Then cut
        // the handler off so nothing else can follow it.
        handler->waitForOutputDrain(150);
        handler->revokeControl();
    }
    detachAgent(id);
}

void TcpServer::dropLocalEnded(const QString &agentId)
{
    const QString id = agentId.trimmed();
    if (id.isEmpty()) {
        return;
    }
    const quint32 epoch = m_listEpoch.value(id, 0);
    if (epoch != 0) {
        const quint32 prev = m_endedEpoch.value(id, 0);
        if (epoch > prev) {
            m_endedEpoch.insert(id, epoch);
        }
    }
    m_claimed.remove(id);
    m_controlKeys.remove(id);
    ClientHandler *handler = nullptr;
    {
        QMutexLocker lock(&m_sessionMutex);
        m_agents.remove(id);
        handler = m_handlers.take(id);
    }
    if (handler) {
        closeHandler(handler, false);
    }
    emit clientRemoved(id);
}

void TcpServer::expireAgentSession(const QString &agentId)
{
    const QString id = agentId.trimmed();
    if (id.isEmpty()) {
        return;
    }
    m_claimed.remove(id);
    m_controlKeys.remove(id);
    if (m_control && m_control->state() == QAbstractSocket::ConnectedState) {
        QJsonObject msg;
        msg.insert(QStringLiteral("type"), QStringLiteral("expire"));
        msg.insert(QStringLiteral("agentId"), id);
        m_control->write(Packet::encodeControlJson(msg));
        m_control->flush();
    }
    if (ClientHandler *handler = handlerForId(id)) {
        handler->waitForOutputDrain(150);
        handler->revokeControl();
    }
    detachAgent(id);
}

void TcpServer::detachAgent(const QString &agentId)
{
    ClientHandler *handler = nullptr;
    {
        QMutexLocker lock(&m_sessionMutex);
        handler = m_handlers.take(agentId);
        m_attaching.remove(agentId);
    }
    if (handler) {
        closeHandler(handler, false);
    }
}

void TcpServer::writeClaim(const QString &agentId, bool claimed, bool endShare)
{
    if (!m_control || m_control->state() != QAbstractSocket::ConnectedState) {
        return;
    }
    QJsonObject msg;
    msg.insert(QStringLiteral("type"),
               claimed ? QStringLiteral("claim") : QStringLiteral("release"));
    msg.insert(QStringLiteral("agentId"), agentId);
    if (!claimed && endShare) {
        msg.insert(QStringLiteral("endShare"), true);
    }
    m_control->write(Packet::encodeControlJson(msg));
    m_control->flush();
}

ClientHandler *TcpServer::attachAgent(const QString &agentId)
{
    if (agentId.isEmpty()) {
        return nullptr;
    }

    bool missing = false;
    ClientInfo identity;
    {
        QMutexLocker lock(&m_sessionMutex);
        if (ClientHandler *existing = m_handlers.value(agentId, nullptr)) {
            return existing;
        }
        if (!m_agents.contains(agentId)) {
            missing = true;
        } else if (m_attaching.contains(agentId)) {
            return nullptr;
        } else {
            identity = m_agents.value(agentId);
            m_attaching.insert(agentId);
        }
    }
    if (missing) {
        emit logMessage(QStringLiteral("%1 Agent %2 is not online on the relay")
                            .arg(formatTimestamp(), agentId),
                        false);
        return nullptr;
    }
    identity.id = agentId;

    auto *socket = new QTcpSocket;
    socket->setSocketOption(QAbstractSocket::LowDelayOption, 1);
    socket->setSocketOption(QAbstractSocket::KeepAliveOption, 1);
    // TCP cannot have more data in flight than the receive window, so on a long
    // path the window is what limits throughput, not the line. Measured RTT to the
    // relay here is 392ms, where the previous 128KB window capped this connection at
    // 128KB / 0.392s = 327KB/s — below what the phone sends during full-screen
    // motion, so frames piled up in the relay on a link that was not actually full.
    // 512KB covers a 400ms path at over 1MB/s, and being one pipe's worth of data it
    // adds no staleness beyond what the distance already costs.
    socket->setSocketOption(QAbstractSocket::ReceiveBufferSizeSocketOption, 512 * 1024);
    socket->setSocketOption(QAbstractSocket::SendBufferSizeSocketOption, 512 * 1024);
    socket->setReadBufferSize(1024 * 1024);
    socket->connectToHost(m_relayHost, m_relayPort);
    if (!socket->waitForConnected(8000)) {
        emit logMessage(QStringLiteral("%1 Attach failed for %2: %3")
                            .arg(formatTimestamp(), agentId, socket->errorString()),
                        false);
        socket->deleteLater();
        QMutexLocker lock(&m_sessionMutex);
        m_attaching.remove(agentId);
        return nullptr;
    }

    QJsonObject hello;
    hello.insert(QStringLiteral("type"), QStringLiteral("hello"));
    hello.insert(QStringLiteral("role"), QStringLiteral("attach"));
    hello.insert(QStringLiteral("agentId"), agentId);
    hello.insert(QStringLiteral("desktopId"), m_desktopId);
    socket->write(Packet::encodeControlJson(hello));
    socket->flush();
    socket->waitForBytesWritten(3000);

    ClientHandler *handler = adoptSocket(socket, identity);
    {
        QMutexLocker lock(&m_sessionMutex);
        m_attaching.remove(agentId);
    }
    emit logMessage(QStringLiteral("%1 Attached to %2 — requesting live screen")
                        .arg(formatTimestamp(), agentId),
                    false);
    return handler;
}

void TcpServer::connectToRelay()
{
    if (!m_wantConnected) {
        return;
    }
    if (m_control->state() == QAbstractSocket::ConnectedState
        || m_control->state() == QAbstractSocket::ConnectingState) {
        return;
    }
    m_controlRx.clear();
    emit logMessage(QStringLiteral("%1 Connecting to relay %2:%3")
                        .arg(formatTimestamp())
                        .arg(m_relayHost)
                        .arg(m_relayPort),
                    true);
    m_control->connectToHost(m_relayHost, m_relayPort);
}

void TcpServer::onControlConnected()
{
    m_listEpoch.clear();
    m_port = m_relayPort;
    emit logMessage(QStringLiteral("Launch at %1").arg(formatTimestamp()), true);
    emit logMessage(QStringLiteral("Relay : %1:%2")
                        .arg(m_relayHost)
                        .arg(m_relayPort),
                    true);
    emit logMessage(QStringLiteral("%1 Connected to relay — waiting for available mobiles")
                        .arg(formatTimestamp()),
                    false);
    sendDesktopHello();
}

void TcpServer::sendDesktopHello()
{
    QJsonObject hello;
    hello.insert(QStringLiteral("type"), QStringLiteral("hello"));
    hello.insert(QStringLiteral("role"), QStringLiteral("desktop"));
    hello.insert(QStringLiteral("desktopId"), m_desktopId);
    // Identity the phone shows on its "Connected to you" card.
    const QString host = QSysInfo::machineHostName().trimmed();
    hello.insert(QStringLiteral("name"),
                 host.isEmpty() ? QStringLiteral("Support controller") : host);
    hello.insert(QStringLiteral("detail"),
                 QStringLiteral("%1 • MenuOnline").arg(m_desktopId.left(8)));
    hello.insert(QStringLiteral("device"),
                 QStringLiteral("%1 • MenuOnline")
                     .arg(QSysInfo::prettyProductName()));
    m_control->write(Packet::encodeControlJson(hello));
    m_control->flush();
    m_heartbeatTimer->start();
    // Re-ask for any preview that is still open here. The relay treats a losing
    // controller as gone for good, so this is expected to come back denied and the
    // window will close itself; it only succeeds when the relay still had us.
    const QList<QString> claimed = m_claimed.values();
    for (const QString &id : claimed) {
        writeClaim(id, true);
    }
}

void TcpServer::onControlDisconnected()
{
    m_heartbeatTimer->stop();
    m_controlRx.clear();
    m_listEpoch.clear();
    QStringList ids;
    QList<ClientHandler *> handlers;
    {
        QMutexLocker lock(&m_sessionMutex);
        ids = m_agents.keys();
        m_agents.clear();
        handlers = m_handlers.values();
        m_handlers.clear();
    }
    for (ClientHandler *handler : handlers) {
        closeHandler(handler, false);
    }
    for (const QString &id : ids) {
        emit clientDisconnected(id);
    }
    emit logMessage(QStringLiteral("%1 Relay disconnected")
                        .arg(formatTimestamp()),
                    false);
    if (m_wantConnected) {
        m_reconnectTimer->start(2000);
    }
}

void TcpServer::onControlError(QAbstractSocket::SocketError)
{
    if (!m_wantConnected) {
        return;
    }
    if (m_control->state() == QAbstractSocket::ConnectedState) {
        return;
    }
    emit logMessage(QStringLiteral("%1 Relay error: %2")
                        .arg(formatTimestamp(), m_control->errorString()),
                    false);
    if (!m_reconnectTimer->isActive()) {
        m_reconnectTimer->start(2000);
    }
}

void TcpServer::onReconnectTimer()
{
    connectToRelay();
}

void TcpServer::onControlReadyRead()
{
    m_controlRx.append(m_control->readAll());
    pumpControlPackets();
}

void TcpServer::pumpControlPackets()
{
    while (m_controlRx.size() >= Packet::kHeaderSize) {
        const char flag = m_controlRx.at(0);
        if (flag != Packet::kFlagControl && flag != Packet::kFlagVideo) {
            m_controlRx.remove(0, 1);
            continue;
        }
        const auto *raw = reinterpret_cast<const uchar *>(m_controlRx.constData());
        const quint32 len = qFromBigEndian<quint32>(raw + 1);
        const quint32 maxLen = (flag == Packet::kFlagVideo)
            ? Packet::kMaxVideoBytes
            : Packet::kMaxControlBytes;
        if (len == 0 || len > maxLen) {
            m_controlRx.remove(0, 1);
            continue;
        }
        if (static_cast<quint32>(m_controlRx.size()) < quint32(Packet::kHeaderSize) + len) {
            return;
        }
        const QByteArray payload = m_controlRx.mid(Packet::kHeaderSize, int(len));
        m_controlRx.remove(0, Packet::kHeaderSize + int(len));
        if (flag == Packet::kFlagControl) {
            handleRelayJson(payload);
        }
    }
}

void TcpServer::handleRelayJson(const QByteArray &payload)
{
    const QJsonDocument doc = QJsonDocument::fromJson(payload.trimmed());
    if (!doc.isObject()) {
        return;
    }
    const QJsonObject object = doc.object();
    const QString type = object.value(QStringLiteral("type")).toString();
    if (type == QLatin1String("peer_online")) {
        applyPeerOnline(object);
        return;
    }
    if (type == QLatin1String("peer_offline")) {
        applyPeerOffline(object.value(QStringLiteral("id")).toString(),
                         object.value(QStringLiteral("busy")).toBool(false),
                         object.value(QStringLiteral("ended")).toBool(false),
                         quint32(object.value(QStringLiteral("sessionId")).toDouble()));
        return;
    }
    if (type == QLatin1String("claim_ok")) {
        const QString agentId = object.value(QStringLiteral("agentId")).toString().trimmed();
        if (!agentId.isEmpty()) {
            const QString key = object.value(QStringLiteral("controlKey")).toString();
            m_claimed.insert(agentId);
            m_controlKeys.insert(agentId, key);
            // A handler may already exist when this answers a re-claim after a
            // relay reconnect, so the live one needs the new key too.
            if (ClientHandler *live = handlerForId(agentId)) {
                live->setControlKey(key);
            }
            emit agentClaimed(agentId,
                              qint64(object.value(QStringLiteral("remainMs")).toDouble()),
                              key);
        }
        return;
    }
    if (type == QLatin1String("claim_denied")) {
        const QString agentId = object.value(QStringLiteral("agentId")).toString().trimmed();
        if (!agentId.isEmpty()) {
            m_claimed.remove(agentId);
            m_controlKeys.remove(agentId);
            emit agentClaimDenied(agentId,
                                  object.value(QStringLiteral("reason")).toString());
        }
        return;
    }
    if (type == QLatin1String("ack") || type == QLatin1String("error")) {
        const QString error = object.value(QStringLiteral("error")).toString();
        if (!error.isEmpty()) {
            emit logMessage(QStringLiteral("%1 Relay: %2")
                                .arg(formatTimestamp(), error),
                            false);
        }
    }
}

void TcpServer::applyPeerOnline(const QJsonObject &object)
{
    ClientInfo info;
    info.id = object.value(QStringLiteral("id")).toString().trimmed();
    if (info.id.isEmpty()) {
        return;
    }
    if (!noteListEpoch(info.id, quint32(object.value(QStringLiteral("sessionId")).toDouble()))) {
        return;
    }
    info.phoneNumber = info.id;
    info.deviceName = object.value(QStringLiteral("device")).toString();
    info.model = object.value(QStringLiteral("model")).toString();
    info.osVersion = object.value(QStringLiteral("os")).toString();
    info.ipAddress = object.value(QStringLiteral("ip")).toString();
    info.connectedAt = QDateTime::currentDateTime().toString(Qt::ISODate);
    info.status = QStringLiteral("Online");

    {
        QMutexLocker lock(&m_sessionMutex);
        if (m_agents.contains(info.id) && !m_agents.value(info.id).connectedAt.isEmpty()) {
            info.connectedAt = m_agents.value(info.id).connectedAt;
        }
        m_agents.insert(info.id, info);
    }

    emit clientRegistered(info);
    emit logMessage(QStringLiteral("%1 Mobile online: %2 [%3]")
                        .arg(formatTimestamp(), info.phoneNumber, info.ipAddress),
                    false);
}

bool TcpServer::noteListEpoch(const QString &agentId, quint32 sessionId)
{
    if (agentId.isEmpty() || sessionId == 0) {
        return true;
    }
    const quint32 ended = m_endedEpoch.value(agentId, 0);
    if (ended != 0 && sessionId <= ended) {
        return false;
    }
    if (ended != 0) {
        m_endedEpoch.remove(agentId);
    }
    const quint32 prev = m_listEpoch.value(agentId, 0);
    if (sessionId < prev) {
        return false;
    }
    m_listEpoch.insert(agentId, sessionId);
    return true;
}

void TcpServer::applyPeerOffline(const QString &agentId, bool busy, bool ended, quint32 sessionId)
{
    if (agentId.isEmpty()) {
        return;
    }
    if (!noteListEpoch(agentId, sessionId)) {
        return;
    }
    if (ended && sessionId != 0) {
        const quint32 prev = m_endedEpoch.value(agentId, 0);
        if (sessionId > prev) {
            m_endedEpoch.insert(agentId, sessionId);
        }
    }
    // Another operator has the preview open. Drop it from this list without
    // tearing down a handler or marking the phone offline. A real end wins:
    // the phone left, so the row has to go even if it was hidden as busy.
    if (busy && !ended) {
        emit clientBusy(agentId);
        return;
    }
    ClientHandler *handler = nullptr;
    {
        QMutexLocker lock(&m_sessionMutex);
        m_agents.remove(agentId);
        handler = m_handlers.take(agentId);
    }
    if (handler) {
        closeHandler(handler, false);
    }
    if (ended) {
        m_claimed.remove(agentId);
        emit clientRemoved(agentId);
        emit logMessage(QStringLiteral("%1 Sharing ended: %2")
                            .arg(formatTimestamp(), agentId),
                        false);
        return;
    }
    emit clientDisconnected(agentId);
    emit logMessage(QStringLiteral("%1 Mobile offline: %2")
                        .arg(formatTimestamp(), agentId),
                    false);
}

ClientHandler *TcpServer::adoptSocket(QTcpSocket *socket, const ClientInfo &identity)
{
    socket->setParent(nullptr);
    auto *handler = new ClientHandler(socket);
    handler->bindIdentity(identity);
    handler->setUdpRelay(m_relayHost, 9000, m_desktopId);
    handler->setControlKey(m_controlKeys.value(identity.id));

    auto *ioThread = new QThread(this);
    handler->moveToThread(ioThread);
    connect(handler, &ClientHandler::registered, this, &TcpServer::onHandlerRegistered);
    connect(handler, &ClientHandler::disconnected, this, &TcpServer::onHandlerDisconnected);
    connect(handler, &ClientHandler::messageReceived, this, &TcpServer::onHandlerMessage);

    ClientHandler *old = nullptr;
    {
        QMutexLocker lock(&m_sessionMutex);
        old = m_handlers.take(identity.id);
        m_handlers.insert(identity.id, handler);
    }
    if (old) {
        closeHandler(old, false);
    }
    ioThread->start();
    QMetaObject::invokeMethod(handler, &ClientHandler::startWorkers, Qt::QueuedConnection);
    return handler;
}

void TcpServer::closeHandler(ClientHandler *handler, bool emitOffline)
{
    if (!handler) {
        return;
    }
    const QString id = handler->clientId();
    disconnect(handler, &ClientHandler::disconnected, this, &TcpServer::onHandlerDisconnected);
    disconnect(handler, &ClientHandler::registered, this, &TcpServer::onHandlerRegistered);
    disconnect(handler, &ClientHandler::messageReceived, this, &TcpServer::onHandlerMessage);
    if (emitOffline) {
        emit clientDisconnected(id);
    }
    reapHandler(handler);
}

void TcpServer::reapHandler(ClientHandler *handler)
{
    if (!handler) {
        return;
    }
    // The worker blocks its own QThread event loop inside receiveLoop, so nothing
    // will quit the thread for us. Signal the loop to stop, let the thread's exec()
    // resume and return, then join before deleting — otherwise the QThread object
    // is torn down while its thread is still running and the app crashes on exit.
    QThread *thr = handler->thread();
    handler->forceClose();
    if (thr && thr != QThread::currentThread()) {
        thr->quit();
        if (!thr->wait(3000)) {
            // The read loop may still have been parked behind a blocking wait;
            // nudge it once more before resorting to terminate(), which can crash
            // the process if it fires mid-syscall.
            handler->forceClose();
            thr->quit();
            if (!thr->wait(3000)) {
                thr->terminate();
                thr->wait();
            }
        }
        delete handler;
        delete thr;
    } else {
        handler->deleteLater();
    }
}

void TcpServer::onHandlerRegistered(const ClientInfo &info)
{
    emit logMessage(QStringLiteral("%1 Screen session ready: %2")
                        .arg(formatTimestamp(), info.phoneNumber),
                    false);
}

void TcpServer::onHandlerDisconnected(const QString &clientId)
{
    auto *handler = qobject_cast<ClientHandler *>(sender());
    {
        QMutexLocker lock(&m_sessionMutex);
        m_handlers.remove(clientId);
    }
    if (handler) {
        disconnect(handler, &ClientHandler::disconnected, this, &TcpServer::onHandlerDisconnected);
        disconnect(handler, &ClientHandler::registered, this, &TcpServer::onHandlerRegistered);
        disconnect(handler, &ClientHandler::messageReceived, this, &TcpServer::onHandlerMessage);
        reapHandler(handler);
    }
    emit logMessage(QStringLiteral("%1 Screen session closed: %2")
                        .arg(formatTimestamp(), clientId),
                    false);
}

void TcpServer::onHandlerMessage(const QString &clientId, const QString &message)
{
    emit clientMessage(clientId, message);
}

QString TcpServer::formatTimestamp() const
{
    return QDateTime::currentDateTime().toString(QStringLiteral("ddd MMM dd HH:mm:ss t yyyy"));
}
