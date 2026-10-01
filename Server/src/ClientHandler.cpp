#include "ClientHandler.h"
#include "Packet.h"
#include "WebrtcSession.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QEvent>
#include <QImage>
#include <QPainter>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QList>
#include <QMutexLocker>
#include <QRandomGenerator>
#include <QUuid>
#include <QHostAddress>
#include <QMetaObject>
#include <QThread>
#include <QtEndian>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <algorithm>
#include <cstring>
#include <utility>

namespace {
constexpr int kRecvWaitMs = 1;

// Safety bound for tile updates, which cannot be dropped individually because each
// one paints over part of the previous picture. Far above anything the pipeline
// should reach; a whole-screen JPEG is what normally clears a backlog.
constexpr int kMaxQueuedTileFrames = 90;

int countQueuedFrames(const QList<IncomingMessage> &queue)
{
    int frames = 0;
    for (const IncomingMessage &message : queue) {
        if (message.flag == Packet::kFlagVideo) {
            ++frames;
        }
    }
    return frames;
}

bool dropOldestQueuedFrame(QList<IncomingMessage> *queue)
{
    for (int i = 0; i < queue->size(); ++i) {
        if (queue->at(i).flag == Packet::kFlagVideo) {
            queue->removeAt(i);
            return true;
        }
    }
    return false;
}

// Drop every queued video frame, keeping control messages in order. Used when a
// fresh H.264 keyframe arrives: it is a self-contained restart, so any older
// frames still waiting are stale and can be shed without corrupting decode.
int dropAllQueuedFrames(QList<IncomingMessage> *queue)
{
    int removed = 0;
    for (int i = 0; i < queue->size();) {
        if (queue->at(i).flag == Packet::kFlagVideo) {
            queue->removeAt(i);
            ++removed;
        } else {
            ++i;
        }
    }
    return removed;
}

class FlushOutEvent : public QEvent
{
public:
    static QEvent::Type staticType()
    {
        static const QEvent::Type t = static_cast<QEvent::Type>(QEvent::registerEventType());
        return t;
    }
    FlushOutEvent()
        : QEvent(staticType())
    {
    }
};
}

ClientHandler::ClientHandler(QTcpSocket *socket, QObject *parent)
    : QObject(parent)
    , m_socket(socket)
{
    m_info.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_info.ipAddress = socket->peerAddress().toString();
    if (m_info.ipAddress.startsWith(QStringLiteral("::ffff:"))) {
        m_info.ipAddress = m_info.ipAddress.mid(7);
    }
    m_info.connectedAt = QDateTime::currentDateTime().toString(Qt::ISODate);
    m_info.status = QStringLiteral("Connected");
    m_info.phoneNumber = QStringLiteral("Unknown");
    m_info.deviceName = QStringLiteral("-");
    m_info.model = QStringLiteral("-");
    m_info.osVersion = QStringLiteral("-");

    m_socket->setParent(this);
}

ClientHandler::~ClientHandler()
{
    stopWorkers();
}

ClientInfo ClientHandler::info() const
{
    QMutexLocker lock(&m_infoMutex);
    return m_info;
}

void ClientHandler::startWorkers()
{
    if (m_processThread.joinable()) {
        return;
    }
    m_stop.store(false);
    m_sendThread = std::thread([this] { sendLoop(); });
    m_inputThread = std::thread([this] { inputLoop(); });
    m_processThread = std::thread([this] { processLoop(); });
    receiveLoop();
    stopWorkers();
    notifyDisconnected();
}

void ClientHandler::stopWorkers()
{
    m_stop.store(true);
    m_queueWait.wakeAll();
    m_inputWait.wakeAll();
    m_tcpOutWait.wakeAll();
    if (m_inputThread.joinable() && m_inputThread.get_id() != std::this_thread::get_id()) {
        m_inputThread.join();
    }
    if (m_sendThread.joinable() && m_sendThread.get_id() != std::this_thread::get_id()) {
        m_sendThread.join();
    }
    if (m_rtc) {
        m_rtc->close();
        m_rtc.reset();
    }
    if (m_processThread.joinable()) {
        if (m_processThread.get_id() != std::this_thread::get_id()) {
            m_processThread.join();
        }
    }
    resetVideoAssembly();
    delete m_udp;
    m_udp = nullptr;
}

void ClientHandler::bindIdentity(const ClientInfo &info)
{
    QMutexLocker lock(&m_infoMutex);
    if (!info.id.isEmpty()) {
        m_info.id = info.id;
    }
    if (!info.phoneNumber.isEmpty()) {
        m_info.phoneNumber = info.phoneNumber;
    }
    if (!info.deviceName.isEmpty()) {
        m_info.deviceName = info.deviceName;
    }
    if (!info.model.isEmpty()) {
        m_info.model = info.model;
    }
    if (!info.osVersion.isEmpty()) {
        m_info.osVersion = info.osVersion;
    }
    if (!info.ipAddress.isEmpty()) {
        m_info.ipAddress = info.ipAddress;
    }
    if (!info.connectedAt.isEmpty()) {
        m_info.connectedAt = info.connectedAt;
    }
    m_info.status = QStringLiteral("Online");
    m_registered = true;
}

void ClientHandler::setUdpRelay(const QString &host, quint16 port, const QString &desktopId)
{
    m_udpHost = host;
    m_udpPort = port;
    m_desktopId = desktopId;
}

QByteArray ClientHandler::wrapUdp(char kind, const QByteArray &body) const
{
    const QByteArray id = m_desktopId.toUtf8().left(255);
    const QByteArray agent = m_info.id.toUtf8().left(255);
    QByteArray out;
    out.reserve(8 + id.size() + agent.size() + body.size());
    out.append("RCUD", 4);
    out.append(kind);
    out.append(char(2));
    out.append(char(id.size()));
    out.append(id);
    if (kind == 1) {
        out.append(char(agent.size()));
        out.append(agent);
    } else {
        out.append(body);
    }
    return out;
}

void ClientHandler::openUdp()
{
    QMutexLocker lock(&m_ioMutex);
    if (m_udp || m_udpHost.isEmpty() || m_udpPort == 0 || m_desktopId.isEmpty()) {
        return;
    }
    auto *sock = new QUdpSocket();
    bool bound = false;
    for (quint16 port = 9001; port <= 9005; ++port) {
        if (sock->bind(QHostAddress(QHostAddress::AnyIPv4), port,
                       QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint)) {
            bound = true;
            break;
        }
    }
    if (!bound) {
        delete sock;
        return;
    }
    // A frame arrives as a burst of ~30 datagrams and one lost slice discards the
    // whole frame, so the default Windows buffer must not be what overflows.
    sock->setSocketOption(QAbstractSocket::ReceiveBufferSizeSocketOption, 8 * 1024 * 1024);
    m_udp = sock;
    m_udpHelloTimer.start();
    m_udpHelloStarted = true;
    sendUdpHello();
}

void ClientHandler::noteInputPeer(const QString &ip)
{
    QString host = ip.trimmed();
    if (host.startsWith(QLatin1String("::ffff:"))) {
        host = host.mid(7);
    }
    if (host.isEmpty() || host == QLatin1String("0.0.0.0")) {
        return;
    }
    QMutexLocker lock(&m_inputHostMutex);
    if (m_inputHost == host) {
        return;
    }
    m_inputHost = host;
    emit messageReceived(m_info.id, QStringLiteral("input TCP peer %1:9001").arg(host));
}

void ClientHandler::openDirectTcp(const QString &ip, quint16 port)
{
    Q_UNUSED(port);
    noteInputPeer(ip);
}

void ClientHandler::punchDirectTcp()
{
    QMutexLocker lock(&m_ioMutex);
    if (m_tcpPeerHost.isEmpty() || m_tcpPeerPort == 0 || !m_socket) {
        return;
    }
    if (m_directTcp && m_directTcp->state() == QAbstractSocket::ConnectedState) {
        if (!m_directTcpUp) {
            m_directTcpUp = true;
            emit messageReceived(m_info.id, QStringLiteral("TCP direct: connected"));
        }
        return;
    }
    m_directTcpUp = false;
    if (m_tcpPunchStarted && m_tcpPunchTimer.elapsed() < 2000) {
        return;
    }
    if (m_directTcp) {
        m_directTcp->abort();
        m_directTcp->deleteLater();
        m_directTcp = nullptr;
    }
    auto *sock = new QTcpSocket();
    sock->setSocketOption(QAbstractSocket::LowDelayOption, 1);
    sock->bind(m_socket->localAddress(), m_socket->localPort(), QAbstractSocket::ReuseAddressHint);
    sock->connectToHost(m_tcpPeerHost, m_tcpPeerPort);
    m_directTcp = sock;
    m_tcpPunchTimer.start();
    m_tcpPunchStarted = true;
    emit messageReceived(m_info.id, QStringLiteral("TCP direct: connecting %1:%2")
                                        .arg(m_tcpPeerHost)
                                        .arg(m_tcpPeerPort));
}

bool ClientHandler::writeDirectTcp(const QByteArray &packet)
{
    if (!m_directTcp || m_directTcp->state() != QAbstractSocket::ConnectedState || packet.isEmpty()) {
        return false;
    }
    const qint64 n = m_directTcp->write(packet);
    if (n != packet.size()) {
        return false;
    }
    m_directTcp->flush();
    return true;
}

void ClientHandler::sendUdpHello()
{
    if (!m_udp || m_info.id.isEmpty()) {
        return;
    }
    const QByteArray hello = wrapUdp(1, QByteArray());
    m_udp->writeDatagram(hello, QHostAddress(m_udpHost), m_udpPort);
}

void ClientHandler::drainUdp()
{
    QMutexLocker lock(&m_ioMutex);
    if (!m_udp) {
        return;
    }
    if (!m_udpHelloStarted || m_udpHelloTimer.elapsed() > 1000) {
        sendUdpHello();
        if (m_peerPort != 0) {
            m_udp->writeDatagram(wrapUdp(5, QByteArray()), m_peerHost, m_peerPort);
        }
        m_udpHelloTimer.restart();
        m_udpHelloStarted = true;
    }
    while (m_udp->hasPendingDatagrams()) {
        QByteArray datagram;
        datagram.resize(int(m_udp->pendingDatagramSize()));
        QHostAddress sender;
        quint16 senderPort = 0;
        if (m_udp->readDatagram(datagram.data(), datagram.size(), &sender, &senderPort) < 8) {
            continue;
        }
        if (m_peerPort != 0 && sender == m_peerHost && senderPort == m_peerPort) {
            m_direct = true;
        }
        if (datagram.left(4) != QByteArray("RCUD", 4)) {
            continue;
        }
        if (datagram.at(4) == 3) {
            m_udpReady = true;
            continue;
        }
        if (datagram.at(4) == 4 && datagram.size() >= 13) {
            const auto *ip = reinterpret_cast<const uchar *>(datagram.constData() + 7);
            m_peerHost = QHostAddress(qFromBigEndian<quint32>(ip));
            m_peerPort = static_cast<quint16>((static_cast<unsigned char>(datagram.at(11)) << 8)
                    | static_cast<unsigned char>(datagram.at(12)));
            noteInputPeer(m_peerHost.toString());
            m_udp->writeDatagram(wrapUdp(5, QByteArray()), m_peerHost, m_peerPort);
            continue;
        }
        if (datagram.at(4) == 5) {
            m_direct = true;
            continue;
        }
        if (datagram.at(4) != 2) {
            continue;
        }
        const int idLen = static_cast<unsigned char>(datagram.at(6));
        const int body = 7 + idLen;
        if (idLen < 0 || body >= datagram.size()) {
            continue;
        }
        QByteArray one(datagram.constData() + body, datagram.size() - body);
        pumpPacketsToQueue(one);
    }
}

void ClientHandler::sendUdpPackets(const QByteArray &data)
{
    QMutexLocker lock(&m_ioMutex);
    if (!m_udp || data.isEmpty()) {
        return;
    }
    int offset = 0;
    while (offset + Packet::kHeaderSize <= data.size()) {
        const char flag = data.at(offset);
        if (flag != Packet::kFlagVideo && flag != Packet::kFlagControl) {
            ++offset;
            continue;
        }
        const auto *raw = reinterpret_cast<const uchar *>(data.constData() + offset);
        const quint32 len = qFromBigEndian<quint32>(raw + 1);
        const int total = Packet::kHeaderSize + int(len);
        if (len == 0 || offset + total > data.size() || total > 1800) {
            break;
        }
        const QByteArray one = wrapUdp(2, data.mid(offset, total));
        // Mouse and key packets are small and must survive a one-way punch.
        // Video can go straight to the phone; input always goes through the relay.
        if (flag != Packet::kFlagControl && m_direct && m_peerPort != 0) {
            m_udp->writeDatagram(one, m_peerHost, m_peerPort);
        } else {
            m_udp->writeDatagram(one, QHostAddress(m_udpHost), m_udpPort);
        }
        offset += total;
    }
}

void ClientHandler::sendCommand(const QString &action, const QJsonObject &payload)
{
    QMutexLocker lock(&m_inputMutex);
    m_inputQueue.append(qMakePair(action, payload));
    m_inputWait.wakeOne();
}

void ClientHandler::forceClose()
{
    m_stop.store(true);
    m_queueWait.wakeAll();
    if (QThread::currentThread() != thread()) {
        QMetaObject::invokeMethod(this, [this] {
            if (m_rtc) {
                m_rtc->close();
            }
            if (m_socket) {
                m_socket->abort();
            }
        }, Qt::QueuedConnection);
        return;
    }
    if (m_rtc) {
        m_rtc->close();
    }
    if (m_socket) {
        m_socket->abort();
    }
}

void ClientHandler::setPreviewTargetSize(const QSize &size)
{
    QMutexLocker lock(&m_frameMutex);
    m_previewTarget = size;
}

void ClientHandler::receiveLoop()
{
    while (!m_stop.load()) {
        openUdp();
        punchDirectTcp();
        drainUdp();
        drainSideChannels();
        if (m_iceRestart.exchange(false)) {
            restartOfferer();
        }
        if (m_rtcFailed) {
            break;
        }
        if (!m_socket) {
            break;
        }
        if (m_socket->state() != QAbstractSocket::ConnectedState) {
            break;
        }

        qint64 available = m_socket->bytesAvailable();
        if (available <= 0) {
            m_socket->waitForReadyRead(kRecvWaitMs);
            if (m_stop.load()) {
                break;
            }
            if (!m_socket || m_socket->state() != QAbstractSocket::ConnectedState) {
                break;
            }
            available = m_socket->bytesAvailable();
        }
        if (available <= 0) {
            continue;
        }

        const QByteArray chunk = m_socket->read(available);
        if (chunk.isEmpty()) {
            if (m_socket->state() != QAbstractSocket::ConnectedState) {
                break;
            }
            continue;
        }
        m_rxBuffer.append(chunk);
        pumpPacketsToQueue(m_rxBuffer);
    }
}

void ClientHandler::pumpPacketsToQueue(QByteArray &buf)
{
    while (buf.size() >= Packet::kHeaderSize) {
        const char flag = buf.at(0);
        if (flag != Packet::kFlagVideo && flag != Packet::kFlagControl) {
            buf.remove(0, 1);
            continue;
        }
        const auto *raw = reinterpret_cast<const uchar *>(buf.constData());
        const quint32 len = qFromBigEndian<quint32>(raw + 1);
        const quint32 maxLen = (flag == Packet::kFlagVideo)
            ? Packet::kMaxVideoBytes
            : Packet::kMaxControlBytes;
        if (len == 0 || len > maxLen) {
            buf.remove(0, 1);
            continue;
        }
        if (static_cast<quint32>(buf.size()) < quint32(Packet::kHeaderSize) + len) {
            return;
        }
        const QByteArray payload = buf.mid(Packet::kHeaderSize, int(len));
        buf.remove(0, Packet::kHeaderSize + int(len));
        if (flag == Packet::kFlagControl) {
            const QJsonDocument doc = QJsonDocument::fromJson(payload.trimmed());
            if (doc.isObject()) {
                const QString type = doc.object().value(QStringLiteral("type")).toString();
                if (type == QLatin1String("tcp_peer")) {
                    openDirectTcp(doc.object().value(QStringLiteral("ip")).toString(),
                                  static_cast<quint16>(doc.object().value(QStringLiteral("port")).toInt()));
                    continue;
                }
                if (type == QLatin1String("pair_ok") || type == QLatin1String("sdp")
                    || type == QLatin1String("ice")) {
                    handleSignaling(doc.object());
                    continue;
                }
            }
        }
        if (flag == Packet::kFlagVideo) {
            appendVideoChunk(payload);
            continue;
        }
        IncomingMessage message;
        message.flag = flag;
        message.payload = payload;
        enqueueMessage(std::move(message));
    }
}

void ClientHandler::enqueueMessage(IncomingMessage message)
{
    QMutexLocker lock(&m_queueMutex);
    if (message.flag == Packet::kFlagVideo) {
        const auto *bytes = reinterpret_cast<const uchar *>(message.payload.constData());
        const int len = message.payload.size();
        m_rxFrames.fetch_add(1);
        m_rxBytes.fetch_add(len);
        if (Packet::looksTiles(bytes, len)) {
            // A tile update paints over part of the picture already on screen, so it
            // only makes sense applied in order — the same dependency H.264 P-frames
            // have. It may not be dropped individually; a whole-screen JPEG is what
            // clears the backlog (below). The bound here is a safety valve only:
            // tile updates are small and decode in well under a frame interval, but
            // an unbounded queue would grow without limit if that ever stopped
            // holding.
            if (countQueuedFrames(m_queue) > kMaxQueuedTileFrames) {
                m_rxDropped.fetch_add(dropAllQueuedFrames(&m_queue));
            }
        } else if (Packet::looksJpeg(bytes, len)) {
            // A whole picture supersedes everything queued behind it, tile updates
            // included: it replaces the canvas those updates were patching.
            m_rxDropped.fetch_add(dropAllQueuedFrames(&m_queue));
        } else if (Packet::looksAnnexB(bytes, len)) {
            // A queued frame is already late. Showing it in order is the 1–2s
            // delay. Keep only the newest picture; the next keyframe clears
            // any torn prediction.
            if (countQueuedFrames(m_queue) > 0) {
                m_rxDropped.fetch_add(dropAllQueuedFrames(&m_queue));
            }
        }
    }
    m_queue.append(std::move(message));
    m_queueWait.wakeOne();
}

void ClientHandler::processLoop()
{
    // Owned by this thread so the Media Foundation decoder's COM/MF lifetime
    // stays on a single thread. Unused (and cheap) while the phone sends JPEG.
    H264Decoder decoder;
    while (true) {
        IncomingMessage message;
        {
            QMutexLocker lock(&m_queueMutex);
            while (m_queue.isEmpty() && !m_stop.load()) {
                m_queueWait.wait(&m_queueMutex, 2);
            }
            if (m_queue.isEmpty()) {
                if (m_stop.load()) {
                    break;
                }
                continue;
            }
            message = m_queue.takeFirst();
            if (message.flag == Packet::kFlagVideo) {
                for (int i = m_queue.size() - 1; i >= 0; --i) {
                    if (m_queue.at(i).flag == Packet::kFlagVideo) {
                        message = m_queue.takeAt(i);
                        m_rxDropped.fetch_add(dropAllQueuedFrames(&m_queue));
                        break;
                    }
                }
            }
        }
        if (message.flag == Packet::kFlagVideo) {
            decodeAndPublish(message, decoder);
        } else if (message.flag == Packet::kFlagControl) {
            handleControlPayload(message.payload);
        }
    }
}

void ClientHandler::resetVideoAssembly()
{
    m_videoWidth = 0;
    m_videoHeight = 0;
    m_videoChunkCount = 0;
    m_videoNextChunk = 0;
    m_videoGot = 0;
    m_videoJpegTotal = 0;
    m_videoFrameId = 0;
    m_videoAssembling = false;
    m_videoJpeg.clear();
    m_videoParts.clear();
}

// Runs on the receive thread. Chunks are only appended here; a completed JPEG is
// handed to the queue as one entry so it can never be dropped half-way.
void ClientHandler::appendVideoChunk(const QByteArray &payload)
{
    if (payload.size() < Packet::kVideoPrefixSize) {
        return;
    }
    const auto *raw = reinterpret_cast<const uchar *>(payload.constData());
    const int width = int(qFromBigEndian<quint32>(raw));
    const int height = int(qFromBigEndian<quint32>(raw + 4));
    const int chunkIndex = int(qFromBigEndian<quint16>(raw + 8));
    const int chunkCount = int(qFromBigEndian<quint16>(raw + 10));
    const quint32 jpegTotal = qFromBigEndian<quint32>(raw + 12);
    const quint32 frameId = qFromBigEndian<quint32>(raw + Packet::kVideoFrameIdOffset);
    const int sliceLen = payload.size() - Packet::kVideoPrefixSize;
    if (width < 2 || height < 2 || chunkCount < 1 || chunkIndex < 0 || chunkIndex >= chunkCount) {
        resetVideoAssembly();
        return;
    }
    if (jpegTotal == 0 || jpegTotal > 16u * 1024u * 1024u || sliceLen < 0
            || sliceLen > Packet::kVideoChunkSize) {
        resetVideoAssembly();
        return;
    }
    if (m_videoAssembling && frameId != m_videoFrameId) {
        const quint32 ahead = frameId - m_videoFrameId;
        if (ahead > 0x80000000u) {
            return;
        }
        resetVideoAssembly();
    }
    if (!m_videoAssembling) {
        m_videoAssembling = true;
        m_videoFrameId = frameId;
        m_videoWidth = width;
        m_videoHeight = height;
        m_videoChunkCount = chunkCount;
        m_videoJpegTotal = jpegTotal;
        m_videoParts = QVector<QByteArray>(chunkCount);
        m_videoGot = 0;
    } else if (m_videoWidth != width || m_videoHeight != height
               || m_videoChunkCount != chunkCount || m_videoJpegTotal != jpegTotal) {
        resetVideoAssembly();
        return;
    }
    if (!m_videoParts.at(chunkIndex).isEmpty()) {
        return;
    }
    m_videoParts[chunkIndex] = QByteArray(payload.constData() + Packet::kVideoPrefixSize, sliceLen);
    m_videoGot++;
    if (m_videoGot < m_videoChunkCount) {
        return;
    }
    m_videoJpeg.clear();
    m_videoJpeg.reserve(int(jpegTotal));
    for (const QByteArray &part : m_videoParts) {
        m_videoJpeg.append(part);
    }
    if (quint32(m_videoJpeg.size()) == m_videoJpegTotal) {
        const auto *bytes = reinterpret_cast<const uchar *>(m_videoJpeg.constData());
        const int len = m_videoJpeg.size();
        if (Packet::looksJpeg(bytes, len) || Packet::looksAnnexB(bytes, len)
                || Packet::looksTiles(bytes, len)) {
            IncomingMessage message;
            message.flag = Packet::kFlagVideo;
            message.payload = m_videoJpeg;
            message.width = width;
            message.height = height;
            message.frameId = frameId;
            enqueueMessage(std::move(message));
        }
    }
    resetVideoAssembly();
}

// Paints a tile update onto the persistent canvas and returns the result. The
// canvas is established by a whole-screen JPEG; until one has arrived there is
// nothing underneath to paint onto, so an update is ignored rather than shown
// against black.
bool ClientHandler::compositeTiles(const IncomingMessage &message, QImage *out)
{
    const auto *data = reinterpret_cast<const uchar *>(message.payload.constData());
    const int len = message.payload.size();
    if (len < Packet::kTilesHeaderSize) {
        return false;
    }
    const int cols = data[5];
    const int rows = data[6];
    const int tiles = data[7];
    if (cols < 1 || rows < 1 || tiles < 1 || tiles > cols * rows) {
        return false;
    }
    const int width = message.width;
    const int height = message.height;
    if (width < 2 || height < 2) {
        return false;
    }
    if (m_canvas.isNull() || m_canvas.width() != width || m_canvas.height() != height) {
        return false;
    }

    QPainter painter(&m_canvas);
    int offset = Packet::kTilesHeaderSize;
    for (int i = 0; i < tiles; ++i) {
        if (offset + Packet::kTilesEntryHeaderSize > len) {
            return false;
        }
        const int index = data[offset];
        const quint32 jpegLen = qFromBigEndian<quint32>(data + offset + 1);
        offset += Packet::kTilesEntryHeaderSize;
        if (jpegLen == 0 || quint32(len - offset) < jpegLen || index >= cols * rows) {
            return false;
        }
        QImage tile;
        if (!tile.loadFromData(data + offset, int(jpegLen), "JPG") || tile.isNull()) {
            return false;
        }
        offset += int(jpegLen);
        // Derived exactly as the phone derives it, so the rects line up with no
        // seams and no accumulated rounding drift across the grid.
        const int col = index % cols;
        const int row = index / cols;
        const int x0 = col * width / cols;
        const int y0 = row * height / rows;
        painter.drawImage(x0, y0, tile);
    }
    painter.end();
    // Hand out a deep copy. The canvas is painted over on the very next update, so
    // sharing its buffer with the frame the UI is about to blit would put the two on
    // the same pixels.
    *out = m_canvas.copy();
    return true;
}

void ClientHandler::decodeAndPublish(const IncomingMessage &message, H264Decoder &decoder)
{
    const auto *data = reinterpret_cast<const uchar *>(message.payload.constData());
    const int len = message.payload.size();
    const bool tiles = Packet::looksTiles(data, len);
    const bool jpeg = !tiles && Packet::looksJpeg(data, len);
    const bool annexB = !tiles && !jpeg && Packet::looksAnnexB(data, len);
    QImage image;
    if (tiles) {
        if (!compositeTiles(message, &image) || image.isNull()) {
            return;
        }
    } else if (jpeg) {
        if (!image.loadFromData(message.payload, "JPG") || image.isNull()) {
            return;
        }
    } else if (annexB) {
        // H.264 Annex B. Frames before the first keyframe simply produce no
        // output (decoder returns false); we skip them until a keyframe arrives.
        if (!decoder.decode(data, len, message.width, message.height, &image)
            || image.isNull()) {
            return;
        }
    } else {
        return;
    }
    if (image.format() != QImage::Format_RGB32 && image.format() != QImage::Format_ARGB32) {
        image = image.convertToFormat(QImage::Format_RGB32);
    }
    if (jpeg) {
        // A whole-screen JPEG is the base the following tile updates paint onto, so
        // it has to be kept, not just displayed. The copy keeps the canvas off the
        // buffer handed to the UI, which is about to be painted over.
        m_canvas = image.copy();
    }
    publishScaledFrame(std::move(image), message.width, message.height);
}

void ClientHandler::handleControlPayload(const QByteArray &payload)
{
    const QByteArray line = payload.trimmed();
    if (!line.isEmpty()) {
        processLine(line);
    }
}

void ClientHandler::publishScaledFrame(QImage image, int sourceWidth, int sourceHeight)
{
    QSize target;
    {
        QMutexLocker lock(&m_frameMutex);
        target = m_previewTarget;
    }

    // Shrink here, on the decode thread, so the UI thread only blits. Upscaling is
    // left to the view's cached copy; doing it here would only add decode latency.
    if (target.width() > 1 && target.height() > 1
        && target.width() < image.width() && target.height() < image.height()) {
        QImage fitted = image.scaled(target, Qt::KeepAspectRatio, Qt::FastTransformation);
        if (!fitted.isNull()) {
            image = std::move(fitted);
        }
    }

    // Store the freshest frame and post at most one notification. If a frame is
    // already waiting for the UI, we only overwrite it: the decode thread keeps up
    // with the network while the UI event loop holds a single pending call, so the
    // window always paints the newest scene instead of draining a stale backlog.
    bool notify;
    {
        QMutexLocker lock(&m_frameMutex);
        m_latestFrame = std::move(image);
        m_latestWidth = sourceWidth;
        m_latestHeight = sourceHeight;
        notify = !m_framePending;
        m_framePending = true;
    }
    if (notify) {
        emit frameReady();
    }
}

bool ClientHandler::takeLatestFrame(QImage *out, int *sourceWidth, int *sourceHeight)
{
    QMutexLocker lock(&m_frameMutex);
    if (!m_framePending) {
        return false;
    }
    m_framePending = false;
    if (out) {
        *out = m_latestFrame;
    }
    if (sourceWidth) {
        *sourceWidth = m_latestWidth;
    }
    if (sourceHeight) {
        *sourceHeight = m_latestHeight;
    }
    return true;
}

ClientHandler::VideoCounters ClientHandler::videoCounters() const
{
    VideoCounters c;
    c.frames = m_rxFrames.load();
    c.bytes = m_rxBytes.load();
    c.dropped = m_rxDropped.load();
    return c;
}

void ClientHandler::resyncFrameNotify()
{
    bool notify = false;
    {
        QMutexLocker lock(&m_frameMutex);
        // A frame is already waiting: hand it to the new consumer right away so the
        // window paints without waiting for the phone's next frame. Otherwise just
        // drop the orphaned latch so the next decode notifies again.
        notify = m_framePending && !m_latestFrame.isNull();
        if (!notify) {
            m_framePending = false;
        }
    }
    if (notify) {
        emit frameReady();
    }
}

void ClientHandler::notifyDisconnected()
{
    if (m_disconnectNotified.exchange(true)) {
        return;
    }
    {
        QMutexLocker lock(&m_infoMutex);
        m_info.status = QStringLiteral("Offline");
    }
    emit disconnected(m_info.id);
}

void ClientHandler::processLine(const QByteArray &line)
{
    const QJsonDocument doc = QJsonDocument::fromJson(line);
    if (!doc.isObject()) {
        emit messageReceived(m_info.id, QString::fromUtf8(line));
        return;
    }

    const QJsonObject object = doc.object();
    const QString type = object.value(QStringLiteral("type")).toString();

    if (type == QLatin1String("register")) {
        ClientInfo snapshot;
        {
            QMutexLocker lock(&m_infoMutex);
            QString displayId = object.value(QStringLiteral("id")).toString().trimmed();
            if (displayId.isEmpty()) {
                displayId = object.value(QStringLiteral("phone")).toString().trimmed();
            }
            if (!displayId.isEmpty()) {
                m_info.phoneNumber = displayId;
            }
            m_info.deviceName = object.value(QStringLiteral("device")).toString(m_info.deviceName);
            m_info.model = object.value(QStringLiteral("model")).toString(m_info.model);
            m_info.osVersion = object.value(QStringLiteral("os")).toString(m_info.osVersion);
            m_info.status = QStringLiteral("Online");
            m_registered = true;
            snapshot = m_info;
        }

        QJsonObject ack;
        ack.insert(QStringLiteral("type"), QStringLiteral("ack"));
        ack.insert(QStringLiteral("status"), QStringLiteral("ok"));
        ack.insert(QStringLiteral("clientId"), snapshot.id);
        sendJson(ack);

        emit registered(snapshot);
        emit messageReceived(snapshot.id,
                             QStringLiteral("Client registered: %1 (%2)")
                                 .arg(snapshot.phoneNumber, snapshot.deviceName));
        return;
    }

    if (type == QLatin1String("ack") || type == QLatin1String("error")
        || type == QLatin1String("peer_online") || type == QLatin1String("peer_offline")) {
        return;
    }

    if (type == QLatin1String("heartbeat")) {
        QMutexLocker lock(&m_infoMutex);
        m_info.status = QStringLiteral("Online");
        return;
    }

    if (type == QLatin1String("response") || type == QLatin1String("event")) {
        const QString action = object.value(QStringLiteral("action")).toString();
        const QJsonObject payload = object.value(QStringLiteral("payload")).toObject();
        emit responseReceived(m_info.id, action, payload);
        emit messageReceived(m_info.id,
                             QStringLiteral("[%1] %2")
                                 .arg(action, QString::fromUtf8(
                                          QJsonDocument(payload).toJson(QJsonDocument::Compact))));
        return;
    }

    emit messageReceived(m_info.id, QString::fromUtf8(line));
}

void ClientHandler::sendJson(const QJsonObject &object)
{
    enqueueOutgoing(Packet::encodeControlJson(object));
}

void ClientHandler::signalCaptureStart(const QByteArray &data)
{
    // screen_monitor_start is what turns the phone's sender on. It cannot wait
    // for the data channel: until ICE finishes that channel is closed, the
    // phone stays at streaming=false, and the monitor window never gets a frame.
    int offset = 0;
    while (offset + Packet::kHeaderSize <= data.size()) {
        const char flag = data.at(offset);
        if (flag != Packet::kFlagVideo && flag != Packet::kFlagControl) {
            break;
        }
        const auto *raw = reinterpret_cast<const uchar *>(data.constData() + offset);
        const quint32 len = qFromBigEndian<quint32>(raw + 1);
        const int total = Packet::kHeaderSize + int(len);
        if (len == 0 || offset + total > data.size()) {
            break;
        }
        if (flag == Packet::kFlagControl) {
            const QByteArray payload = data.mid(offset + Packet::kHeaderSize, int(len));
            if (payload.contains("\"screen_monitor_start\"")) {
                queueSignaling(payload);
            }
        }
        offset += total;
    }
}

void ClientHandler::inputLoop()
{
    // The phone runs whichever copy of an event arrives first (UDP direct or TCP)
    // and drops the rest by sequence; the session id resets that window when a new
    // desktop session starts counting from 1 again.
    const double sessionId = double(QRandomGenerator::global()->bounded(1, 0x7fffffff));
    double seq = 0;
    while (!m_stop.load()) {
        QString action;
        QJsonObject payload;
        {
            QMutexLocker lock(&m_inputMutex);
            while (m_inputQueue.isEmpty() && !m_stop.load()) {
                m_inputWait.wait(&m_inputMutex, 1);
            }
            if (m_inputQueue.isEmpty()) {
                break;
            }
            const auto event = m_inputQueue.takeFirst();
            action = event.first;
            payload = event.second;
        }
        payload.insert(QStringLiteral("isid"), sessionId);
        payload.insert(QStringLiteral("iseq"), ++seq);
        const QByteArray packet = Packet::encodeCommand(action, payload);
        if (packet.isEmpty()) {
            continue;
        }
        {
            QMutexLocker lock(&m_tcpOutMutex);
            m_tcpOutQueue.append(packet);
        }
        m_tcpOutWait.wakeOne();
    }
}

bool ClientHandler::writeRelayTcp(const QByteArray &packet)
{
    if (!m_socket || packet.isEmpty()) {
        return false;
    }
    QMutexLocker lock(&m_tcpMutex);
    if (m_socket->state() != QAbstractSocket::ConnectedState) {
        return false;
    }
    const qintptr fd = m_socket->socketDescriptor();
    if (fd < 0) {
        return false;
    }
    const char *p = packet.constData();
    int left = packet.size();
    while (left > 0) {
        const int n = ::send(static_cast<SOCKET>(fd), p, left, 0);
        if (n <= 0) {
            return false;
        }
        p += n;
        left -= n;
    }
    return true;
}

static SOCKET dialInput9001(const QByteArray &host)
{
    const SOCKET s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) {
        return INVALID_SOCKET;
    }
    BOOL reuse = TRUE;
    ::setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<char *>(&reuse), sizeof(reuse));
    u_long nb = 1;
    ::ioctlsocket(s, FIONBIO, &nb);
    sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_port = htons(9001);
    local.sin_addr.s_addr = htonl(INADDR_ANY);
    ::bind(s, reinterpret_cast<sockaddr *>(&local), sizeof(local));
    sockaddr_in remote{};
    remote.sin_family = AF_INET;
    remote.sin_port = htons(9001);
    if (::inet_pton(AF_INET, host.constData(), &remote.sin_addr) != 1) {
        ::closesocket(s);
        return INVALID_SOCKET;
    }
    const int rc = ::connect(s, reinterpret_cast<sockaddr *>(&remote), sizeof(remote));
    if (rc != 0 && WSAGetLastError() != WSAEWOULDBLOCK) {
        ::closesocket(s);
        return INVALID_SOCKET;
    }
    return s;
}

// 1 = connected, 0 = still pending, -1 = failed. Never blocks.
static int pollDial(SOCKET s)
{
    fd_set wfds;
    fd_set efds;
    FD_ZERO(&wfds);
    FD_ZERO(&efds);
    FD_SET(s, &wfds);
    FD_SET(s, &efds);
    timeval tv{};
    const int n = ::select(0, nullptr, &wfds, &efds, &tv);
    if (n == 0) {
        return 0;
    }
    if (n < 0 || FD_ISSET(s, &efds)) {
        return -1;
    }
    int soerr = 0;
    int sl = sizeof(soerr);
    ::getsockopt(s, SOL_SOCKET, SO_ERROR, reinterpret_cast<char *>(&soerr), &sl);
    if (soerr != 0) {
        return -1;
    }
    u_long block = 0;
    ::ioctlsocket(s, FIONBIO, &block);
    BOOL nd = TRUE;
    ::setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<char *>(&nd), sizeof(nd));
    return 1;
}

static bool sendAll(SOCKET s, const QByteArray &packet)
{
    const char *p = packet.constData();
    int left = packet.size();
    while (left > 0) {
        const int n = ::send(s, p, left, 0);
        if (n <= 0) {
            return false;
        }
        p += n;
        left -= n;
    }
    return true;
}

// The hole-punched UDP path the video already arrives on. 9001 TCP needs an open
// port on one side and fails behind carrier NAT, which left input on the relay.
// Sent twice because a lost datagram is not retried; the TCP copy that follows
// covers the case where both are lost.
void ClientHandler::sendInputUdpDirect(const QByteArray &packet)
{
    QMutexLocker lock(&m_ioMutex);
    if (!m_udp || !m_direct || m_peerPort == 0 || packet.size() > 1600) {
        return;
    }
    const qintptr fd = m_udp->socketDescriptor();
    if (fd < 0) {
        return;
    }
    const QByteArray one = wrapUdp(2, packet);
    sockaddr_in to{};
    to.sin_family = AF_INET;
    to.sin_port = htons(m_peerPort);
    to.sin_addr.s_addr = htonl(m_peerHost.toIPv4Address());
    for (int i = 0; i < 2; ++i) {
        ::sendto(static_cast<SOCKET>(fd), one.constData(), one.size(), 0,
                 reinterpret_cast<const sockaddr *>(&to), sizeof(to));
    }
}

void ClientHandler::sendLoop()
{
    SOCKET listenSock = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listenSock != INVALID_SOCKET) {
        BOOL reuse = TRUE;
        ::setsockopt(listenSock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<char *>(&reuse), sizeof(reuse));
        sockaddr_in local{};
        local.sin_family = AF_INET;
        local.sin_port = htons(9001);
        local.sin_addr.s_addr = htonl(INADDR_ANY);
        if (::bind(listenSock, reinterpret_cast<sockaddr *>(&local), sizeof(local)) != 0
                || ::listen(listenSock, 1) != 0) {
            ::closesocket(listenSock);
            listenSock = INVALID_SOCKET;
        } else {
            u_long nb = 1;
            ::ioctlsocket(listenSock, FIONBIO, &nb);
        }
    }
    SOCKET conn = INVALID_SOCKET;
    SOCKET dialing = INVALID_SOCKET;
    QElapsedTimer dialTimer;
    dialTimer.start();
    while (!m_stop.load()) {
        if (listenSock != INVALID_SOCKET) {
            fd_set rfds;
            FD_ZERO(&rfds);
            FD_SET(listenSock, &rfds);
            timeval tv{};
            if (::select(0, &rfds, nullptr, nullptr, &tv) > 0) {
                const SOCKET accepted = ::accept(listenSock, nullptr, nullptr);
                if (accepted != INVALID_SOCKET) {
                    if (conn != INVALID_SOCKET) {
                        ::closesocket(conn);
                    }
                    BOOL nd = TRUE;
                    ::setsockopt(accepted, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<char *>(&nd), sizeof(nd));
                    conn = accepted;
                }
            }
        }
        if (dialing != INVALID_SOCKET) {
            const int state = pollDial(dialing);
            if (state > 0 && conn == INVALID_SOCKET) {
                conn = dialing;
                dialing = INVALID_SOCKET;
            } else if (state != 0 || conn != INVALID_SOCKET || dialTimer.elapsed() > 3000) {
                ::closesocket(dialing);
                dialing = INVALID_SOCKET;
            }
        }
        if (conn == INVALID_SOCKET && dialing == INVALID_SOCKET && dialTimer.elapsed() > 300) {
            dialTimer.restart();
            QByteArray host;
            {
                QMutexLocker lock(&m_inputHostMutex);
                host = m_inputHost.toLatin1();
            }
            if (!host.isEmpty()) {
                dialing = dialInput9001(host);
            }
        }
        QByteArray packet;
        {
            QMutexLocker lock(&m_tcpOutMutex);
            if (m_tcpOutQueue.isEmpty() && !m_stop.load()) {
                m_tcpOutWait.wait(&m_tcpOutMutex, 1);
            }
            if (m_tcpOutQueue.isEmpty()) {
                continue;
            }
            packet = m_tcpOutQueue.takeFirst();
        }
        sendInputUdpDirect(packet);
        if (conn != INVALID_SOCKET && sendAll(conn, packet)) {
            continue;
        }
        if (conn != INVALID_SOCKET) {
            ::closesocket(conn);
            conn = INVALID_SOCKET;
        }
        writeRelayTcp(packet);
    }
    if (dialing != INVALID_SOCKET) {
        ::closesocket(dialing);
    }
    if (conn != INVALID_SOCKET) {
        ::closesocket(conn);
    }
    if (listenSock != INVALID_SOCKET) {
        ::closesocket(listenSock);
    }
}

void ClientHandler::enqueueOutgoing(const QByteArray &data)
{
    if (data.isEmpty()) {
        return;
    }
    QMutexLocker lock(&m_outMutex);
    m_pendingOut.append(data);
}

void ClientHandler::flushPendingOut()
{
    QByteArray data;
    {
        QMutexLocker lock(&m_outMutex);
        data.swap(m_pendingOut);
    }
    if (data.isEmpty()) {
        return;
    }
    sendUdpHello();
    sendUdpPackets(data);
}

void ClientHandler::restartOfferer()
{
    m_iceRetried = true;
    if (!m_rtc) {
        return;
    }
    const QString desktopId = m_rtc->desktopId();
    m_rtc->close();
    auto session = std::make_shared<WebrtcSession>(desktopId);
    session->onLocalSignal = [this](const QByteArray &json) { queueSignaling(json); };
    session->onPacket = [this](const QByteArray &packet) { queuePacket(packet); };
    session->onState = [this](const QString &state) { queueNote(state); };
    m_rtc = session;
    try {
        session->startOfferer(m_iceServers);
    } catch (const std::exception &ex) {
        queueNote(QStringLiteral("failed: %1").arg(QString::fromUtf8(ex.what())));
        m_rtcFailed = true;
    }
    emit messageReceived(m_info.id, QStringLiteral("WebRTC: retrying ICE"));
}

void ClientHandler::queueSignaling(const QByteArray &json)
{
    if (json.isEmpty()) {
        return;
    }
    QMutexLocker lock(&m_sideMutex);
    m_sigOut.append(Packet::encode(Packet::kFlagControl, json));
}

void ClientHandler::queuePacket(const QByteArray &packet)
{
    if (packet.isEmpty()) {
        return;
    }
    QMutexLocker lock(&m_sideMutex);
    m_dcRx.append(packet);
}

void ClientHandler::queueNote(const QString &note)
{
    QMutexLocker lock(&m_sideMutex);
    m_notes.append(note);
    // The first ICE failure is a dead candidate pair, not a dead signaling
    // socket. Tearing the socket down is what delivered screen_monitor_stop
    // and left the phone with no session to retry.
    if (note == QLatin1String("failed") && !m_iceRetried) {
        m_iceRestart.store(true);
    }
}

void ClientHandler::drainSideChannels()
{
    QByteArray signaling;
    QByteArray inbound;
    QStringList notes;
    {
        QMutexLocker lock(&m_sideMutex);
        signaling.swap(m_sigOut);
        inbound.swap(m_dcRx);
        notes.swap(m_notes);
    }
    if (!signaling.isEmpty() && m_socket
        && m_socket->state() == QAbstractSocket::ConnectedState) {
        writeRelayTcp(signaling);
    } else if (!signaling.isEmpty()) {
        QMutexLocker lock(&m_sideMutex);
        signaling.append(m_sigOut);
        m_sigOut = std::move(signaling);
    }
    if (!inbound.isEmpty()) {
        m_rxBuffer.append(inbound);
        pumpPacketsToQueue(m_rxBuffer);
    }
    for (const QString &note : notes) {
        emit messageReceived(m_info.id, QStringLiteral("WebRTC: %1").arg(note));
    }
}

void ClientHandler::handleSignaling(const QJsonObject &object)
{
    const QString type = object.value(QStringLiteral("type")).toString();
    if (type == QLatin1String("pair_ok")) {
        if (m_rtc) {
            m_rtc->close();
            m_rtc.reset();
        }
        emit messageReceived(m_info.id, QStringLiteral("JPEG stream"));
        return;
    }
    if (!m_rtc) {
        return;
    }
    if (type == QLatin1String("sdp")) {
        m_rtc->acceptRemoteSdp(object.value(QStringLiteral("sdpType")).toString(),
                               object.value(QStringLiteral("sdp")).toString());
        return;
    }
    if (type == QLatin1String("ice")) {
        m_rtc->addRemoteCandidate(object.value(QStringLiteral("candidate")).toString(),
                                  object.value(QStringLiteral("mid")).toString());
    }
}

void ClientHandler::customEvent(QEvent *event)
{
    if (event && event->type() == FlushOutEvent::staticType()) {
        flushPendingOut();
        event->accept();
        return;
    }
    QObject::customEvent(event);
}
