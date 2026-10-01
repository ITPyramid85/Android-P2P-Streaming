#pragma once

#include <QByteArray>
#include <QJsonArray>
#include <QString>
#include <QVector>

#include <functional>
#include <memory>

// One WebRTC peer connection for a desktop↔phone screen session.
//
// The desktop is the offerer and creates two data channels:
//   "ctrl"  — reliable ordered control packets (the existing 'C' frames)
//   "video" — reliable ordered video packets (the existing 'V' frames)
//
// SDP and ICE travel through the signaling relay. Once the channels open, every
// media and input byte goes peer-to-peer (or via TURN if both NATs require it).
class WebrtcSession
{
public:
    struct IceServer {
        QString urls;
        QString username;
        QString credential;
    };

    explicit WebrtcSession(QString desktopId);
    QString desktopId() const;

    void startOfferer(const QVector<IceServer> &servers);
    void acceptRemoteSdp(const QString &sdpType, const QString &sdp);
    void addRemoteCandidate(const QString &candidate, const QString &mid);
    // Sends a concatenated stream of [flag][len][payload] packets.
    // Returns how many leading bytes were accepted. 0 when the channels are not open yet.
    int sendStream(const QByteArray &data);
    bool isOpen() const;
    void close();

    std::function<void(const QByteArray &json)> onLocalSignal;
    std::function<void(const QByteArray &packet)> onPacket;
    std::function<void(const QString &state)> onState;

private:
    struct Impl;
    std::shared_ptr<Impl> m_impl;
};
