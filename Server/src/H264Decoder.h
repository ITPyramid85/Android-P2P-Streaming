#pragma once

#include <QByteArray>
#include <QImage>

struct AVCodecContext;
struct AVPacket;
struct AVFrame;
struct SwsContext;

// H.264 (Annex B) decoder backed by FFmpeg's libavcodec. FFmpeg is bundled with
// the app (Server/third_party/ffmpeg) so decoding works on every Windows edition
// — unlike the Media Foundation decoder, which is absent on Windows N/KN without
// the Media Feature Pack and silently left the preview blank there.
//
// The interface is deliberately unchanged from the previous decoder so callers
// (ClientHandler) need no edits: feed each complete access unit, get an RGB
// QImage back. Frames before the first keyframe simply yield no output.
class H264Decoder
{
public:
    H264Decoder();
    ~H264Decoder();

    H264Decoder(const H264Decoder &) = delete;
    H264Decoder &operator=(const H264Decoder &) = delete;

    bool decode(const unsigned char *annexB, int length, int width, int height, QImage *out);
    void reset();

private:
    bool ensureOpen();
    void close();
    bool frameToImage(QImage *out);

    AVCodecContext *m_ctx = nullptr;
    AVPacket *m_packet = nullptr;
    AVFrame *m_frame = nullptr;
    SwsContext *m_sws = nullptr;
    int m_swsSrcW = 0;
    int m_swsSrcH = 0;
    int m_swsSrcFmt = -1;
};
