#pragma once

#include <QByteArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QtEndian>
#include <QtGlobal>
#include <cstring>

// Wire format used by the Android client and this desktop:
//
//   [flag:1][length:4 BE][payload]
//
//   flag 'V'  video chunk
//             payload = width:4 + height:4 + chunkIndex:2 + chunkCount:2
//                       + jpegTotal:4 + frameId:4 + jpegSlice (max 1450 bytes)
//             JPEG frames >= 1450 bytes are split; each chunk is numbered
//             chunkIndex is 0 .. chunkCount-1
//             frameId is the same for every chunk of one JPEG frame
//   flag 'C'  control  payload = UTF-8 JSON
//
// Control JSON (phone → desktop): register, heartbeat, response
// Control JSON (desktop → phone) — RemoteApp:
//   command / input_touch  { a: down|move|up, x, y }
//   command / input_key    { k: androidKeyCode, d: bool }
//   command / input_text   { t: string }
//   command / input_scroll { x, y, v }
namespace Packet {
constexpr char kFlagVideo = 'V';
constexpr char kFlagControl = 'C';
constexpr int kHeaderSize = 5;
constexpr int kVideoChunkSize = 1450;
constexpr int kVideoPrefixSize = 20;
constexpr int kVideoFrameIdOffset = 16;
constexpr quint32 kMaxVideoBytes = quint32(kVideoPrefixSize + kVideoChunkSize);
constexpr quint32 kMaxControlBytes = 1024u * 1024u;

inline QByteArray encode(char flag, const QByteArray &payload)
{
    QByteArray packet(kHeaderSize + payload.size(), Qt::Uninitialized);
    packet[0] = flag;
    qToBigEndian(quint32(payload.size()), reinterpret_cast<uchar *>(packet.data()) + 1);
    if (!payload.isEmpty()) {
        memcpy(packet.data() + kHeaderSize, payload.constData(), size_t(payload.size()));
    }
    return packet;
}

inline QByteArray encodeControlJson(const QJsonObject &object)
{
    return encode(kFlagControl, QJsonDocument(object).toJson(QJsonDocument::Compact));
}

inline QByteArray encodeCommand(const QString &action, const QJsonObject &payload)
{
    QJsonObject object;
    object.insert(QStringLiteral("type"), QStringLiteral("command"));
    object.insert(QStringLiteral("action"), action);
    object.insert(QStringLiteral("payload"), payload);
    return encodeControlJson(object);
}

inline QByteArray encodeTouch(const QString &action, double nx, double ny)
{
    QJsonObject payload;
    payload.insert(QStringLiteral("a"), action);
    payload.insert(QStringLiteral("x"), nx);
    payload.insert(QStringLiteral("y"), ny);
    return encodeCommand(QStringLiteral("input_touch"), payload);
}

inline QByteArray encodeAndroidKey(int keyCode, bool down)
{
    QJsonObject payload;
    payload.insert(QStringLiteral("k"), keyCode);
    payload.insert(QStringLiteral("d"), down);
    return encodeCommand(QStringLiteral("input_key"), payload);
}

inline QByteArray encodeText(const QString &text)
{
    QJsonObject payload;
    payload.insert(QStringLiteral("t"), text);
    return encodeCommand(QStringLiteral("input_text"), payload);
}

inline QByteArray encodeScroll(double nx, double ny, double vertical)
{
    QJsonObject payload;
    payload.insert(QStringLiteral("x"), nx);
    payload.insert(QStringLiteral("y"), ny);
    payload.insert(QStringLiteral("v"), vertical);
    return encodeCommand(QStringLiteral("input_scroll"), payload);
}

inline QByteArray encodeMouse(const QJsonObject &payload)
{
    return encodeCommand(QStringLiteral("input_mouse"), payload);
}

inline QByteArray encodeNavKey(int keyCode)
{
    return encodeAndroidKey(keyCode, true);
}

inline bool looksJpeg(const uchar *p, int n)
{
    return p && n >= 2 && p[0] == 0xFF && p[1] == 0xD8;
}

// H.264 Annex B elementary stream begins with a start code (00 00 01 or
// 00 00 00 01). Used to tell an encoded video frame apart from a JPEG one so
// the desktop can decode either without a protocol change.
inline bool looksAnnexB(const uchar *p, int n)
{
    if (!p) {
        return false;
    }
    if (n >= 4 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x00 && p[3] == 0x01) {
        return true;
    }
    return n >= 3 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x01;
}

// Type of the first NAL unit in an Annex B access unit (nal_unit_type, the low
// 5 bits of the byte after the start code), or -1 if there is no start code.
inline int annexBFirstNalType(const uchar *p, int n)
{
    if (!p) {
        return -1;
    }
    int off = -1;
    if (n >= 4 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x00 && p[3] == 0x01) {
        off = 4;
    } else if (n >= 3 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x01) {
        off = 3;
    }
    if (off < 0 || off >= n) {
        return -1;
    }
    return p[off] & 0x1F;
}

// A keyframe access unit starts with SPS/PPS (the phone prepends codec config to
// every keyframe) or an IDR slice. These are the only points where the H.264
// stream can be re-started, so stale frames may be dropped up to here without
// corrupting decode; dropping anywhere else breaks every following P-frame.
inline bool looksAnnexBKeyframe(const uchar *p, int n)
{
    const int t = annexBFirstNalType(p, n);
    return t == 7 || t == 8 || t == 5;
}

// Tile-update container written by the phone:
//
//   'R','C','T','1' | flags:1 | cols:1 | rows:1 | tileCount:1
//   then per tile:    index:1 | jpegLength:4 BE | jpeg bytes
//
// Only the tiles whose pixels changed are carried, so an update that opens one
// menu costs a fraction of a full screen. A whole screen is sent as a plain JPEG
// instead — past roughly 27 of 32 tiles the per-tile JPEG headers cost more than
// they save, and a plain JPEG is also something a viewer can adopt with no
// history. The magic is neither FF D8 nor an Annex B start code, which is how this
// is told apart from plain JPEG and H.264 without touching the packet header.
constexpr int kTilesHeaderSize = 8;
constexpr int kTilesEntryHeaderSize = 5;

inline bool looksTiles(const uchar *p, int n)
{
    return p && n >= kTilesHeaderSize && p[0] == 'R' && p[1] == 'C' && p[2] == 'T'
            && p[3] == '1';
}

}
