#include "WebrtcSession.h"

#include "Packet.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QPair>
#include <QtEndian>

#include <rtc/rtc.hpp>

#include <mutex>
#include <utility>

namespace {

QString normalizeCandidate(QString candidate)
{
    candidate = candidate.trimmed();
    if (candidate.startsWith(QStringLiteral("a="))) {
        candidate = candidate.mid(2);
    }
    return candidate;
}

rtc::IceServer toIceServer(const WebrtcSession::IceServer &server)
{
    const QString urls = server.urls.trimmed();
    const int scheme = urls.indexOf(QLatin1Char(':'));
    const QString proto = scheme > 0 ? urls.left(scheme) : QString();
    QString rest = scheme > 0 ? urls.mid(scheme + 1) : urls;
    while (rest.startsWith(QLatin1Char('/'))) {
        rest = rest.mid(1);
    }
    const int colon = rest.lastIndexOf(QLatin1Char(':'));
    QString host = colon > 0 ? rest.left(colon) : rest;
    uint16_t port = 3478;
    if (colon > 0) {
        bool ok = false;
        const int parsed = rest.mid(colon + 1).toInt(&ok);
        if (ok && parsed > 0 && parsed < 65536) {
            port = static_cast<uint16_t>(parsed);
        }
    }
    if (proto.startsWith(QStringLiteral("turn"))) {
        const auto relay = proto.contains(QStringLiteral("tcp"), Qt::CaseInsensitive)
            ? rtc::IceServer::RelayType::TurnTcp
            : rtc::IceServer::RelayType::TurnUdp;
        return rtc::IceServer(host.toStdString(), port,
                              server.username.toStdString(),
                              server.credential.toStdString(),
                              relay);
    }
    if (port == 3478 && proto.startsWith(QStringLiteral("stun"))) {
        port = 19302;
    }
    return rtc::IceServer(host.toStdString(), port);
}

} // namespace

struct WebrtcSession::Impl : std::enable_shared_from_this<WebrtcSession::Impl> {
    explicit Impl(QString desktopId)
        : desktopId(std::move(desktopId))
    {
    }

    void emitSignal(const QByteArray &json)
    {
        std::function<void(const QByteArray &)> cb;
        {
            std::lock_guard<std::mutex> lock(mu);
            if (closed) {
                return;
            }
            cb = onLocalSignal;
        }
        if (cb) {
            cb(json);
        }
    }

    void emitPacket(const QByteArray &packet)
    {
        std::function<void(const QByteArray &)> cb;
        {
            std::lock_guard<std::mutex> lock(mu);
            if (closed) {
                return;
            }
            cb = onPacket;
        }
        if (cb) {
            cb(packet);
        }
    }

    void emitState(const QString &state)
    {
        std::function<void(const QString &)> cb;
        {
            std::lock_guard<std::mutex> lock(mu);
            if (closed) {
                return;
            }
            cb = onState;
        }
        if (cb) {
            cb(state);
        }
    }

    void sendSdp(const rtc::Description &description)
    {
        const char *sdpType = "offer";
        if (description.type() == rtc::Description::Type::Answer) {
            sdpType = "answer";
        }
        QJsonObject object;
        object.insert(QStringLiteral("type"), QStringLiteral("sdp"));
        object.insert(QStringLiteral("desktopId"), desktopId);
        object.insert(QStringLiteral("sdpType"), QString::fromLatin1(sdpType));
        object.insert(QStringLiteral("sdp"), QString::fromStdString(std::string(description)));
        emitSignal(QJsonDocument(object).toJson(QJsonDocument::Compact));
    }

    void sendCandidate(const rtc::Candidate &candidate)
    {
        const QString line = normalizeCandidate(QString::fromStdString(candidate.candidate()));
        if (line.isEmpty()) {
            return;
        }
        QString mid = QString::fromStdString(candidate.mid());
        if (mid.isEmpty()) {
            mid = QStringLiteral("0");
        }
        QJsonObject object;
        object.insert(QStringLiteral("type"), QStringLiteral("ice"));
        object.insert(QStringLiteral("desktopId"), desktopId);
        object.insert(QStringLiteral("candidate"), line);
        object.insert(QStringLiteral("mid"), mid);
        if (line.contains(QStringLiteral("typ relay"))) {
            emitState(QStringLiteral("turn"));
        }
        emitSignal(QJsonDocument(object).toJson(QJsonDocument::Compact));
    }

    void flushCandidates()
    {
        QVector<QPair<QString, QString>> pending;
        {
            std::lock_guard<std::mutex> lock(mu);
            pending.swap(queuedCandidates);
        }
        for (const auto &item : pending) {
            addCandidateNow(item.first, item.second);
        }
    }

    void addCandidateNow(const QString &candidate, const QString &mid)
    {
        std::shared_ptr<rtc::PeerConnection> peer;
        {
            std::lock_guard<std::mutex> lock(mu);
            peer = pc;
        }
        if (!peer) {
            return;
        }
        try {
            peer->addRemoteCandidate(rtc::Candidate(candidate.toStdString(), mid.toStdString()));
        } catch (const std::exception &ex) {
            emitState(QStringLiteral("ice error: %1").arg(QString::fromUtf8(ex.what())));
        }
    }

    void noteChannel()
    {
        bool ready = false;
        {
            std::lock_guard<std::mutex> lock(mu);
            ready = ctrl && ctrl->isOpen() && video && video->isOpen() && !announcedOpen;
            if (ready) {
                announcedOpen = true;
            }
        }
        if (ready) {
            emitState(QStringLiteral("connected"));
        }
    }

    void bindChannel(const std::shared_ptr<rtc::DataChannel> &channel)
    {
        auto self = weak_from_this();
        channel->onOpen([self]() {
            if (auto s = self.lock()) {
                s->noteChannel();
            }
        });
        channel->onMessage([self](rtc::message_variant data) {
            auto s = self.lock();
            if (!s) {
                return;
            }
            if (const auto *bin = std::get_if<rtc::binary>(&data)) {
                if (!bin->empty()) {
                    s->emitPacket(QByteArray(reinterpret_cast<const char *>(bin->data()),
                                             int(bin->size())));
                }
            } else if (const auto *text = std::get_if<std::string>(&data)) {
                if (!text->empty()) {
                    s->emitPacket(QByteArray(text->data(), int(text->size())));
                }
            }
        });
    }

    QString desktopId;
    std::mutex mu;
    bool closed = false;
    bool remoteSet = false;
    bool announcedOpen = false;
    std::shared_ptr<rtc::PeerConnection> pc;
    std::shared_ptr<rtc::DataChannel> ctrl;
    std::shared_ptr<rtc::DataChannel> video;
    QVector<QPair<QString, QString>> queuedCandidates;
    std::function<void(const QByteArray &)> onLocalSignal;
    std::function<void(const QByteArray &)> onPacket;
    std::function<void(const QString &)> onState;
};

WebrtcSession::WebrtcSession(QString desktopId)
    : m_impl(std::make_shared<Impl>(std::move(desktopId)))
{
}

QString WebrtcSession::desktopId() const
{
    return m_impl->desktopId;
}

void WebrtcSession::startOfferer(const QVector<IceServer> &servers)
{
    auto impl = m_impl;
    {
        std::lock_guard<std::mutex> lock(impl->mu);
        impl->onLocalSignal = onLocalSignal;
        impl->onPacket = onPacket;
        impl->onState = onState;
    }
    rtc::Configuration config;
    if (servers.isEmpty()) {
        config.iceServers.emplace_back(rtc::IceServer(std::string("stun.l.google.com"), uint16_t(19302)));
    }
    for (const IceServer &server : servers) {
        if (server.urls.trimmed().isEmpty()) {
            continue;
        }
        try {
            config.iceServers.push_back(toIceServer(server));
        } catch (const std::exception &ex) {
            impl->emitState(QStringLiteral("ice server skipped: %1")
                                .arg(QString::fromUtf8(ex.what())));
        }
    }
    if (config.iceServers.empty()) {
        config.iceServers.emplace_back(rtc::IceServer(std::string("stun.l.google.com"), uint16_t(19302)));
    }

    auto pc = std::make_shared<rtc::PeerConnection>(config);
    {
        std::lock_guard<std::mutex> lock(impl->mu);
        impl->pc = pc;
    }

    std::weak_ptr<Impl> self = impl;
    pc->onLocalDescription([self](rtc::Description description) {
        if (auto s = self.lock()) {
            s->sendSdp(description);
        }
    });
    pc->onLocalCandidate([self](rtc::Candidate candidate) {
        if (auto s = self.lock()) {
            s->sendCandidate(candidate);
        }
    });
    pc->onStateChange([self](rtc::PeerConnection::State state) {
        auto s = self.lock();
        if (!s) {
            return;
        }
        switch (state) {
        case rtc::PeerConnection::State::Connecting:
            s->emitState(QStringLiteral("connecting"));
            break;
        case rtc::PeerConnection::State::Connected:
            s->emitState(QStringLiteral("ice-connected"));
            break;
        case rtc::PeerConnection::State::Failed:
            s->emitState(QStringLiteral("failed"));
            break;
        case rtc::PeerConnection::State::Disconnected:
            s->emitState(QStringLiteral("disconnected"));
            break;
        case rtc::PeerConnection::State::Closed:
            s->emitState(QStringLiteral("closed"));
            break;
        default:
            break;
        }
    });

    auto ctrl = pc->createDataChannel("ctrl");
    rtc::DataChannelInit videoInit;
    videoInit.reliability.unordered = true;
    videoInit.reliability.maxRetransmits = 0;
    auto video = pc->createDataChannel("video", videoInit);
    impl->bindChannel(ctrl);
    impl->bindChannel(video);
    {
        std::lock_guard<std::mutex> lock(impl->mu);
        impl->ctrl = std::move(ctrl);
        impl->video = std::move(video);
    }
    impl->emitState(QStringLiteral("gathering"));
}

void WebrtcSession::acceptRemoteSdp(const QString &sdpType, const QString &sdp)
{
    auto impl = m_impl;
    std::shared_ptr<rtc::PeerConnection> pc;
    {
        std::lock_guard<std::mutex> lock(impl->mu);
        if (impl->closed || impl->remoteSet || !impl->pc) {
            return;
        }
        impl->remoteSet = true;
        pc = impl->pc;
    }
    try {
        const auto type = sdpType.compare(QStringLiteral("offer"), Qt::CaseInsensitive) == 0
            ? rtc::Description::Type::Offer
            : rtc::Description::Type::Answer;
        pc->setRemoteDescription(rtc::Description(sdp.toStdString(), type));
    } catch (const std::exception &ex) {
        impl->emitState(QStringLiteral("sdp error: %1").arg(QString::fromUtf8(ex.what())));
        return;
    }
    impl->flushCandidates();
}

void WebrtcSession::addRemoteCandidate(const QString &candidate, const QString &mid)
{
    const QString line = normalizeCandidate(candidate);
    if (line.isEmpty()) {
        return;
    }
    const QString mline = mid.isEmpty() ? QStringLiteral("0") : mid;
    bool queue = false;
    {
        std::lock_guard<std::mutex> lock(m_impl->mu);
        if (m_impl->closed) {
            return;
        }
        if (!m_impl->remoteSet) {
            m_impl->queuedCandidates.append(qMakePair(line, mline));
            queue = true;
        }
    }
    if (!queue) {
        m_impl->addCandidateNow(line, mline);
    }
}

int WebrtcSession::sendStream(const QByteArray &data)
{
    std::shared_ptr<rtc::DataChannel> ctrl;
    std::shared_ptr<rtc::DataChannel> video;
    {
        std::lock_guard<std::mutex> lock(m_impl->mu);
        if (m_impl->closed) {
            return 0;
        }
        ctrl = m_impl->ctrl;
        video = m_impl->video;
    }
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
        const auto channel = flag == Packet::kFlagVideo ? video : ctrl;
        if (!channel || !channel->isOpen()) {
            break;
        }
        try {
            const bool ok = channel->send(
                reinterpret_cast<const std::byte *>(data.constData() + offset),
                size_t(total));
            if (!ok) {
                break;
            }
        } catch (const std::exception &) {
            break;
        }
        offset += total;
    }
    return offset;
}

bool WebrtcSession::isOpen() const
{
    std::lock_guard<std::mutex> lock(m_impl->mu);
    return m_impl->ctrl && m_impl->ctrl->isOpen();
}

void WebrtcSession::close()
{
    std::shared_ptr<rtc::PeerConnection> pc;
    {
        std::lock_guard<std::mutex> lock(m_impl->mu);
        m_impl->closed = true;
        m_impl->onLocalSignal = nullptr;
        m_impl->onPacket = nullptr;
        m_impl->onState = nullptr;
        pc = std::move(m_impl->pc);
        m_impl->ctrl.reset();
        m_impl->video.reset();
    }
    if (pc) {
        try {
            pc->close();
        } catch (const std::exception &) {
        }
    }
}
