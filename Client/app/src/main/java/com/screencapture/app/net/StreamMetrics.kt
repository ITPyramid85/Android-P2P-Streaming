package com.screencapture.app.net

import org.json.JSONObject
import java.util.Locale

/**
 * One-second rollup of the screen pipeline, from capture to the wire.
 *
 * Deliberately independent of the adaptive controller in SilentScreenStreamer.
 * That controller senses the link only through how long a write blocked, which
 * measured the real end-to-end rate on TCP but cannot on UDP: sendto() returns
 * as soon as the local socket buffer accepts the datagram, however congested
 * the path is. So its own linkBps/blocked numbers cannot be used to find where
 * the delay is, and these counters exist to answer that separately.
 *
 * Stages are kept apart on purpose, because each loses frames for a different
 * reason and the fix differs: dropped before the encoder (skip*), lost to
 * encode time (encodeMs), refused by the sender (refused), or lost on the path
 * (the datagram count against the desktop's own report).
 *
 * Nothing here is cumulative — every field is a rate or an average/max over
 * the last second, so one report line describes the present, not the session.
 */
object StreamMetrics {

    private val lock = Any()

    // Capture stage: what the compositor handed us and why frames stopped there.
    private var seen = 0
    private var skipInterval = 0
    private var skipUnchanged = 0
    private var skipQueue = 0
    private var skipBusy = 0
    private var hashMsSum = 0L
    private var hashMsMax = 0L
    private var hashCount = 0

    // Encode stage.
    private var encoded = 0
    private var encodeMsSum = 0L
    private var encodeMsMax = 0L
    private var jpegBytes = 0L
    private var jpegMax = 0

    // Hand-off to the sender.
    private var accepted = 0
    private var refused = 0
    private var pipelineMsSum = 0L
    private var pipelineMsMax = 0L

    // Wire stage.
    private var writes = 0
    private var datagrams = 0
    private var wireBytes = 0L
    private var writeMsSum = 0L
    private var writeMsMax = 0L

    // Capture-to-displayed round trip, sampled by the desktop's frame_rx echo.
    private var loopCount = 0
    private var loopMsSum = 0L
    private var loopMsMin = Long.MAX_VALUE
    private var loopMsMax = 0L

    // Operating point of the adaptive controller, so a report shows which of its
    // levers actually moved during the window.
    @Volatile private var intervalMs = 0L
    @Volatile private var quality = 0
    @Volatile private var scaleIndex = 0
    @Volatile private var linkBps = 0L
    @Volatile private var captureWidth = 0
    @Volatile private var captureHeight = 0
    @Volatile private var direct = false

    private var windowAt = 0L

    @JvmStatic
    fun noteCaptured(hashMs: Long) {
        synchronized(lock) {
            seen++
            hashCount++
            hashMsSum += hashMs
            if (hashMs > hashMsMax) hashMsMax = hashMs
        }
    }

    @JvmStatic
    fun noteSkippedInterval() {
        synchronized(lock) { skipInterval++ }
    }

    @JvmStatic
    fun noteSkippedUnchanged() {
        synchronized(lock) { skipUnchanged++ }
    }

    /** The encoder could not keep up, so offerEncode threw away a queued frame. */
    @JvmStatic
    fun noteSkippedQueue() {
        synchronized(lock) { skipQueue++ }
    }

    /** The previous frame had not left yet, so this one was never encoded. */
    @JvmStatic
    fun noteSkippedBusy() {
        synchronized(lock) { skipBusy++ }
    }

    @JvmStatic
    fun noteEncoded(encodeMs: Long, bytes: Int) {
        synchronized(lock) {
            encoded++
            encodeMsSum += encodeMs
            if (encodeMs > encodeMsMax) encodeMsMax = encodeMs
            jpegBytes += bytes
            if (bytes > jpegMax) jpegMax = bytes
        }
    }

    @JvmStatic
    fun noteHandedOff(taken: Boolean, pipelineMs: Long) {
        synchronized(lock) {
            if (!taken) {
                refused++
                return
            }
            accepted++
            pipelineMsSum += pipelineMs
            if (pipelineMs > pipelineMsMax) pipelineMsMax = pipelineMs
        }
    }

    fun noteWrite(datagramCount: Int, bytes: Int, writeMs: Long) {
        synchronized(lock) {
            writes++
            datagrams += datagramCount
            wireBytes += bytes
            writeMsSum += writeMs
            if (writeMs > writeMsMax) writeMsMax = writeMs
        }
    }

    fun noteLoop(loopMs: Long) {
        if (loopMs < 0) return
        synchronized(lock) {
            loopCount++
            loopMsSum += loopMs
            if (loopMs < loopMsMin) loopMsMin = loopMs
            if (loopMs > loopMsMax) loopMsMax = loopMs
        }
    }

    fun noteTransport(isDirect: Boolean) {
        direct = isDirect
    }

    @JvmStatic
    fun noteOperatingPoint(
        intervalMs: Long,
        quality: Int,
        scaleIndex: Int,
        linkBps: Long,
        width: Int,
        height: Int,
    ) {
        this.intervalMs = intervalMs
        this.quality = quality
        this.scaleIndex = scaleIndex
        this.linkBps = linkBps
        this.captureWidth = width
        this.captureHeight = height
    }

    /**
     * Close the window and describe it, or return null if less than a second has
     * passed. Called from the 1 s heartbeat so a stalled stream still reports
     * zeroes instead of going silent, which is the case worth seeing.
     */
    fun takeReport(nowMs: Long): String? {
        synchronized(lock) {
            if (windowAt == 0L) {
                windowAt = nowMs
                return null
            }
            val windowMs = nowMs - windowAt
            if (windowMs < WINDOW_MS) {
                return null
            }
            val line = format(windowMs)
            reset(nowMs)
            return line
        }
    }

    fun reportJson(line: String): String =
        JSONObject().put("type", "stream_stats").put("line", line).toString()

    private fun reset(nowMs: Long) {
        windowAt = nowMs
        seen = 0
        skipInterval = 0
        skipUnchanged = 0
        skipQueue = 0
        skipBusy = 0
        hashMsSum = 0
        hashMsMax = 0
        hashCount = 0
        encoded = 0
        encodeMsSum = 0
        encodeMsMax = 0
        jpegBytes = 0
        jpegMax = 0
        accepted = 0
        refused = 0
        pipelineMsSum = 0
        pipelineMsMax = 0
        writes = 0
        datagrams = 0
        wireBytes = 0
        writeMsSum = 0
        writeMsMax = 0
        loopCount = 0
        loopMsSum = 0
        loopMsMin = Long.MAX_VALUE
        loopMsMax = 0
    }

    private fun format(windowMs: Long): String {
        val perSec = 1000.0 / windowMs.coerceAtLeast(1)
        val sb = StringBuilder(320)
        sb.append("cap ").append(rate(seen, perSec))
        sb.append(" skip(int ").append(skipInterval)
            .append(" same ").append(skipUnchanged)
            .append(" queue ").append(skipQueue)
            .append(" busy ").append(skipBusy).append(')')
        sb.append(" hash ").append(ms(hashMsSum, hashCount, hashMsMax))
        sb.append(" | enc ").append(rate(encoded, perSec))
            .append(' ').append(ms(encodeMsSum, encoded, encodeMsMax))
            .append(" jpeg ").append(kb(if (encoded > 0) jpegBytes / encoded else 0L))
            .append('/').append(kb(jpegMax.toLong()))
        sb.append(" | tx ").append(rate(accepted, perSec))
            .append(" refused ").append(refused)
            .append(" pipe ").append(ms(pipelineMsSum, accepted, pipelineMsMax))
        sb.append(" | wire ").append(kb((wireBytes * perSec).toLong())).append("/s ")
            .append(rate(datagrams, perSec)).append(" dg")
            .append(" write ").append(ms(writeMsSum, writes, writeMsMax))
        sb.append(" | loop ")
        if (loopCount > 0) {
            sb.append(loopMsSum / loopCount).append("ms min ").append(loopMsMin)
                .append(" max ").append(loopMsMax).append(" n").append(loopCount)
        } else {
            sb.append("no echo")
        }
        sb.append(" | ").append(captureWidth).append('x').append(captureHeight)
            .append(" q").append(quality)
            .append(" int").append(intervalMs).append("ms")
            .append(" scale").append(scaleIndex)
            .append(" link ").append(kb(linkBps))
            .append("/s path ").append(if (direct) "direct" else "relay")
        return sb.toString()
    }

    private fun rate(count: Int, perSec: Double): String =
        String.format(Locale.US, "%.1f/s", count * perSec)

    private fun ms(sum: Long, count: Int, max: Long): String =
        if (count > 0) "${sum / count}/${max}ms" else "-/-ms"

    private fun kb(bytes: Long): String =
        if (bytes >= 1024) "${bytes / 1024}k" else "${bytes}B"

    const val TAG = "StreamMetrics"
    private const val WINDOW_MS = 1000L
}
