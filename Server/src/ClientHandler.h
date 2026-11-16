#pragma once

#include <QObject>
#include <QElapsedTimer>
#include <QTcpSocket>
#include <QUdpSocket>
#include <QByteArray>
#include <QEvent>
#include <QImage>
#include <QJsonObject>
#include <QList>
#include <QPair>
#include <QMutex>
#include <QRecursiveMutex>
#include <QSize>
#include <QString>
#include <QStringList>
#include <QVector>
#include <QWaitCondition>
#include <QtGlobal>
#include <atomic>
#include <memory>
#include <thread>

#include "WebrtcSession.h"

#include "H264Decoder.h"

struct ClientInfo
{
    QString id;
    QString phoneNumber;
    QString deviceName;
    QString model;
    QString osVersion;
    QString ipAddress;
    QString connectedAt;
    QString status;
};

// Video entries carry a whole assembled JPEG, never a single chunk, so dropping a
// stale entry can never truncate a frame that is already half-assembled.
struct IncomingMessage
{
    char flag = 0;
    QByteArray payload;
    int width = 0;
    int height = 0;
    quint32 frameId = 0;
};

class ClientHandler : public QObject
{
    Q_OBJECT

public:
    explicit ClientHandler(QTcpSocket *socket, QObject *parent = nullptr);
    ~ClientHandler() override;

    QString clientId() const { return m_info.id; }
    ClientInfo info() const;
    void bindIdentity(const ClientInfo &info);

    void setUdpRelay(const QString &host, quint16 port, const QString &desktopId);
    // Key the relay minted when this controller was granted the mobile. Every input
    // event carries it; the phone ignores input stamped with any other key, which
    // is what cuts off a controller whose session has ended but whose direct
    // UDP / TCP path to the phone is still open.
    void setControlKey(const QString &key);
    // This controller's session is over. Forgets the key, throws away whatever it
    // had queued, and refuses anything sent afterwards, so no event can leave this
    // PC once the operator has stopped — including events already handed over by
    // the window but not yet on the wire.
    void revokeControl();
    // Lets the queued traffic reach the phone before the handler is torn down.
    // Returns once both queues are empty or the timeout expires. Without it,
    // forceClose() stops the worker loops where they stand and the screen_monitor_stop
    // that Stop had just queued is thrown away unsent.
    void waitForOutputDrain(int timeoutMs);
    void sendCommand(const QString &action, const QJsonObject &payload = QJsonObject());
    void forceClose();
    void setPreviewTargetSize(const QSize &size);

    // Pulls the newest decoded frame, if one is waiting. Returns false when the UI
    // has already consumed the latest frame. The decode thread overwrites the held
    // frame in place, so the UI always renders the freshest scene and never a
    // backlog — see publishScaledFrame for why this coalescing exists.
    bool takeLatestFrame(QImage *out, int *sourceWidth, int *sourceHeight);

    // Re-arms notifications for a viewer that just bound to this handler. Only
    // takeLatestFrame() clears the pending latch, so a window closed while its
    // notification was still queued left the latch set forever and the decode
    // thread stopped emitting — the reopened window then waited for video that
    // could never arrive. Call this whenever the consumer changes or looks stuck.
    void resyncFrameNotify();

    // Running totals for the receive side, so a window can tell whether frames are
    // arriving and being thrown away here or never arriving at all. Without this,
    // a slow relay->desktop leg and a slow phone look identical on screen.
    //
    // chunks against missingChunks is the loss measurement that matters here: a
    // frame is split across ~30 datagrams and appendVideoChunk can only publish it
    // once every one of them has arrived, so a single lost slice costs the whole
    // frame. incomplete counts the frames that died that way.
    struct VideoCounters {
        qint64 frames = 0;
        qint64 bytes = 0;
        qint64 dropped = 0;
        qint64 datagrams = 0;
        qint64 chunks = 0;
        qint64 dupChunks = 0;
        qint64 lateChunks = 0;
        qint64 incomplete = 0;
        qint64 missingChunks = 0;
        qint64 decodeCount = 0;
        qint64 decodeMs = 0;
        qint64 gapCount = 0;
        qint64 gapMs = 0;
    };
    VideoCounters videoCounters() const;

signals:
    // Emitted from the decode thread. Connect with the receiver as context so Qt
    // drops the connection when that receiver dies; a raw callback capturing a
    // dialog would keep firing into freed memory during shutdown.
    //
    // Parameterless and coalesced: the decode thread posts at most one pending
    // notification into the UI event loop no matter how many frames it decodes
    // meanwhile, then the slot pulls the newest frame with takeLatestFrame(). A
    // per-frame queued signal used to pile up unbounded when painting was slower
    // than decode+network, so the frame counter froze and then jumped 30-50 at
    // once while the UI replayed a backlog of already-stale frames.
    void frameReady();
    void registered(const ClientInfo &info);
    void disconnected(const QString &clientId);
    // The phone pressed Stop. The preview must close; a stream disconnect alone
    // used to leave the window up and attach again.
    void remoteShareEnded();
    void messageReceived(const QString &clientId, const QString &message);
    void responseReceived(const QString &clientId, const QString &action, const QJsonObject &payload);

public slots:
    void startWorkers();

protected:
    void customEvent(QEvent *event) override;

private:
    void receiveLoop();
    void processLoop();
    void sendLoop();
    void sendInputUdpDirect(const QByteArray &packet);
    void inputLoop();
    bool writeRelayTcp(const QByteArray &packet);
    void stopWorkers();
    void pumpPacketsToQueue(QByteArray &buf);
    void enqueueMessage(IncomingMessage message);
    void appendVideoChunk(const QByteArray &payload);
    void decodeAndPublish(const IncomingMessage &message, H264Decoder &decoder);
    bool compositeTiles(const IncomingMessage &message, QImage *out);
    void handleControlPayload(const QByteArray &payload);
    void resetVideoAssembly();
    void reportVideoStats();
    void publishScaledFrame(QImage image, int sourceWidth, int sourceHeight);
    void processLine(const QByteArray &line);
    void sendJson(const QJsonObject &object);
    void queueControlJson(const QJsonObject &object);
    void enqueueOutgoing(const QByteArray &data);
    void flushPendingOut();
    void signalCaptureStart(const QByteArray &data);
    void notifyDisconnected();
    void handleSignaling(const QJsonObject &object);
    void drainSideChannels();
    void queueSignaling(const QByteArray &json);
    void queuePacket(const QByteArray &packet);
    void queueNote(const QString &note);
    void restartOfferer();
    void openUdp();
    void sendUdpHello();
    void drainUdp();
    void sendUdpPackets(const QByteArray &data);
    QByteArray wrapUdp(char kind, const QByteArray &body) const;
    void openDirectTcp(const QString &ip, quint16 port);
    void punchDirectTcp();
    bool writeDirectTcp(const QByteArray &packet);
    void noteInputPeer(const QString &ip);

    QTcpSocket *m_socket = nullptr;
    QByteArray m_rxBuffer;
    ClientInfo m_info;
    mutable QMutex m_infoMutex;
    bool m_registered = false;
    std::atomic<bool> m_disconnectNotified{false};
    std::atomic<bool> m_stop{false};

    QMutex m_queueMutex;
    QWaitCondition m_queueWait;
    QList<IncomingMessage> m_queue;
    std::thread m_processThread;
    std::thread m_sendThread;
    std::thread m_inputThread;
    QMutex m_controlKeyMutex;
    QString m_controlKey;
    std::atomic<bool> m_controlRevoked{false};
    QMutex m_inputMutex;
    QWaitCondition m_inputWait;
    QList<QPair<QString, QJsonObject>> m_inputQueue;
    QMutex m_tcpOutMutex;
    QWaitCondition m_tcpOutWait;
    QList<QByteArray> m_tcpOutQueue;
    QMutex m_tcpMutex;

    // Full-resolution picture the phone's tile updates are painted onto. Owned by
    // the process thread alone: each update rewrites only the regions that changed,
    // so this is the only place the complete current scene exists.
    QImage m_canvas;

    QMutex m_frameMutex;
    QSize m_previewTarget;
    // Newest decoded frame awaiting the UI. Guarded by m_frameMutex; m_framePending
    // gates how many notifications are outstanding so the UI queue never grows.
    QImage m_latestFrame;
    int m_latestWidth = 0;
    int m_latestHeight = 0;
    bool m_framePending = false;

    mutable QMutex m_outMutex;
    QByteArray m_pendingOut;

    // Signaling JSON waiting to be written on the relay socket, and media/control
    // packets that arrived on the data channel. The receive loop owns the socket
    // and the packet parser, so the WebRTC threads only append here.
    QMutex m_sideMutex;
    QByteArray m_sigOut;
    QByteArray m_dcRx;
    QStringList m_notes;
    std::shared_ptr<WebrtcSession> m_rtc;
    QVector<WebrtcSession::IceServer> m_iceServers;
    bool m_iceRetried = false;
    std::atomic<bool> m_iceRestart{false};
    bool m_rtcFailed = false;

    QString m_udpHost;
    quint16 m_udpPort = 0;
    QString m_desktopId;
    QUdpSocket *m_udp = nullptr;
    QRecursiveMutex m_ioMutex;
    QElapsedTimer m_udpHelloTimer;
    bool m_udpHelloStarted = false;
    bool m_udpReady = false;
    bool m_direct = false;
    QHostAddress m_peerHost;
    quint16 m_peerPort = 0;
    QTcpSocket *m_directTcp = nullptr;
    QMutex m_inputHostMutex;
    QString m_inputHost;
    QString m_tcpPeerHost;
    quint16 m_tcpPeerPort = 0;
    QElapsedTimer m_tcpPunchTimer;
    bool m_tcpPunchStarted = false;
    bool m_directTcpUp = false;

    std::atomic<qint64> m_rxFrames{0};
    std::atomic<qint64> m_rxBytes{0};
    std::atomic<qint64> m_rxDropped{0};
    std::atomic<qint64> m_rxDatagrams{0};
    std::atomic<qint64> m_rxChunks{0};
    std::atomic<qint64> m_rxDupChunks{0};
    std::atomic<qint64> m_rxLateChunks{0};
    std::atomic<qint64> m_rxIncomplete{0};
    std::atomic<qint64> m_rxMissingChunks{0};
    std::atomic<qint64> m_decodeCount{0};
    std::atomic<qint64> m_decodeMs{0};
    std::atomic<qint64> m_decodeMsMax{0};
    std::atomic<qint64> m_gapCount{0};
    std::atomic<qint64> m_gapMs{0};
    std::atomic<qint64> m_gapMsMax{0};

    // Window state for the once-per-second report. Owned by the receive thread,
    // except m_frameGapTimer which belongs to the process thread.
    QElapsedTimer m_statTimer;
    bool m_statStarted = false;
    VideoCounters m_statLast;
    QElapsedTimer m_frameGapTimer;
    // Rate limit for the frame_rx echo the phone uses to measure the round trip.
    QElapsedTimer m_echoTimer;
    bool m_echoStarted = false;
    qint64 m_echoChunks = 0;
    qint64 m_echoMissing = 0;

    int m_videoWidth = 0;
    int m_videoHeight = 0;
    int m_videoChunkCount = 0;
    int m_videoNextChunk = 0;
    int m_videoGot = 0;
    quint32 m_videoJpegTotal = 0;
    quint32 m_videoFrameId = 0;
    bool m_videoAssembling = false;
    QByteArray m_videoJpeg;
    QVector<QByteArray> m_videoParts;
};
