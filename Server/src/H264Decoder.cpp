#include "H264Decoder.h"

// FFmpeg's fixed-width macros (AV_NOPTS_VALUE etc.) need these before <cstdint>.
#ifndef __STDC_CONSTANT_MACROS
#define __STDC_CONSTANT_MACROS
#endif
#ifndef __STDC_LIMIT_MACROS
#define __STDC_LIMIT_MACROS
#endif

#include <QtGlobal>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

H264Decoder::H264Decoder()
{
    av_log_set_level(AV_LOG_ERROR);
}

H264Decoder::~H264Decoder()
{
    close();
}

void H264Decoder::reset()
{
    close();
}

void H264Decoder::close()
{
    if (m_sws) {
        sws_freeContext(m_sws);
        m_sws = nullptr;
    }
    if (m_frame) {
        av_frame_free(&m_frame);
    }
    if (m_packet) {
        av_packet_free(&m_packet);
    }
    if (m_ctx) {
        avcodec_free_context(&m_ctx);
    }
    m_swsSrcW = 0;
    m_swsSrcH = 0;
    m_swsSrcFmt = -1;
}

bool H264Decoder::ensureOpen()
{
    if (m_ctx) {
        return true;
    }
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec) {
        qWarning("H264Decoder: no H.264 decoder in bundled FFmpeg");
        return false;
    }
    m_ctx = avcodec_alloc_context3(codec);
    if (!m_ctx) {
        return false;
    }
    // Single-threaded + low-delay so each access unit decodes to a frame right
    // away; frame-threading would buffer a few frames and add visible latency.
    m_ctx->thread_count = 1;
    m_ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    m_ctx->flags2 |= AV_CODEC_FLAG2_FAST;

    const int rc = avcodec_open2(m_ctx, codec, nullptr);
    if (rc < 0) {
        qWarning("H264Decoder: avcodec_open2 failed rc=%d", rc);
        avcodec_free_context(&m_ctx);
        return false;
    }
    m_packet = av_packet_alloc();
    m_frame = av_frame_alloc();
    if (!m_packet || !m_frame) {
        close();
        return false;
    }
    return true;
}

bool H264Decoder::frameToImage(QImage *out)
{
    const int w = m_frame->width;
    const int h = m_frame->height;
    if (w < 2 || h < 2) {
        return false;
    }
    const int srcFmt = m_frame->format;
    if (!m_sws || m_swsSrcW != w || m_swsSrcH != h || m_swsSrcFmt != srcFmt) {
        if (m_sws) {
            sws_freeContext(m_sws);
            m_sws = nullptr;
        }
        // BGRA byte order equals QImage::Format_RGB32 (0xFFRRGGBB) on little
        // endian, so no extra channel swizzle is needed after scaling.
        m_sws = sws_getContext(w, h, static_cast<AVPixelFormat>(srcFmt),
                               w, h, AV_PIX_FMT_BGRA,
                               SWS_BILINEAR, nullptr, nullptr, nullptr);
        if (!m_sws) {
            return false;
        }
        m_swsSrcW = w;
        m_swsSrcH = h;
        m_swsSrcFmt = srcFmt;
    }

    QImage image(w, h, QImage::Format_RGB32);
    if (image.isNull()) {
        return false;
    }
    uint8_t *dstData[4] = {image.bits(), nullptr, nullptr, nullptr};
    int dstStride[4] = {static_cast<int>(image.bytesPerLine()), 0, 0, 0};
    const int scaled = sws_scale(m_sws, m_frame->data, m_frame->linesize, 0, h,
                                 dstData, dstStride);
    if (scaled <= 0) {
        return false;
    }
    *out = std::move(image);
    return true;
}

bool H264Decoder::decode(const unsigned char *annexB, int length, int width, int height,
                         QImage *out)
{
    (void) width;
    (void) height;
    if (!annexB || length <= 0 || !out) {
        return false;
    }
    if (!ensureOpen()) {
        return false;
    }

    // Borrow the caller's buffer for the packet: buf stays null so no ownership
    // is taken and nothing is freed. libavcodec copies what it needs internally.
    m_packet->data = const_cast<uint8_t *>(annexB);
    m_packet->size = length;
    m_packet->pts = AV_NOPTS_VALUE;
    m_packet->dts = AV_NOPTS_VALUE;

    const int sendRc = avcodec_send_packet(m_ctx, m_packet);
    if (sendRc < 0 && sendRc != AVERROR(EAGAIN) && sendRc != AVERROR_EOF) {
        // A bad or early packet — typically a P-frame that arrived before the
        // first keyframe, or one whose reference was lost to a dropped frame.
        // Skip it but keep the decoder open: it recovers on its own at the next
        // keyframe, with no costly close/reopen and no extra keyframe wait.
        return false;
    }

    bool got = false;
    for (;;) {
        const int recvRc = avcodec_receive_frame(m_ctx, m_frame);
        if (recvRc == AVERROR(EAGAIN) || recvRc == AVERROR_EOF) {
            break;
        }
        if (recvRc < 0) {
            break;
        }
        if (frameToImage(out)) {
            got = true;
        }
        av_frame_unref(m_frame);
    }
    return got && !out->isNull();
}
