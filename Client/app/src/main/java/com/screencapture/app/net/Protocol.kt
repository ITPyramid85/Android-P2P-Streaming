package com.screencapture.app.net

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * TCP packets are length-prefixed so video and control never mix:
 *
 *   [flag:1][length:4 BE][payload]
 *
 *   'V' video chunk
 *       payload = width:4 + height:4 + chunkIndex:2 + chunkCount:2
 *                 + jpegTotal:4 + frameId:4 + jpegSlice (max 1450 bytes)
 *       JPEG >= 1450 bytes is split; each chunk has a 0-based index
 *       frameId is per JPEG frame and is the same on every chunk of that frame
 *   'C' control  payload = UTF-8 JSON
 *
 * Phone → desktop control JSON: register, heartbeat, response
 * Desktop → phone control JSON (RemoteApp):
 *   command / input_touch  { a: down|move|up, x, y }
 *   command / input_key    { k: androidKeyCode, d: bool }
 *   command / input_text   { t: string }
 *   command / input_scroll { x, y, v }
 */
object Protocol {
    const val FLAG_VIDEO: Byte = 0x56 // 'V'
    const val FLAG_CONTROL: Byte = 0x43 // 'C'
    const val PACKET_HEADER_SIZE = 5
    const val VIDEO_CHUNK_SIZE = 1450
    const val VIDEO_PREFIX_SIZE = 20
    const val MAX_VIDEO_BYTES = VIDEO_PREFIX_SIZE + VIDEO_CHUNK_SIZE
    const val MAX_CONTROL_BYTES = 1024 * 1024

    const val ACTION_SCREEN_START = "screen_monitor_start"
    const val ACTION_SCREEN_STOP = "screen_monitor_stop"
    const val ACTION_INPUT_TOUCH = "input_touch"
    const val ACTION_INPUT_KEY = "input_key"
    const val ACTION_INPUT_TEXT = "input_text"
    const val ACTION_INPUT_SCROLL = "input_scroll"
    const val ACTION_INPUT_MOUSE = "input_mouse"

    const val MOUSE_CLICK = "click"
    const val MOUSE_SWIPE = "swipe"
    const val MOUSE_LONGPRESS = "longpress"
    const val MOUSE_SCROLL = "scroll"

    const val KEY_BACK = "back"
    const val KEY_HOME = "home"
    const val KEY_RECENTS = "recents"

    fun registerJson(id: String, device: String, model: String, os: String): String {
        return JSONObject()
            .put("type", "register")
            .put("id", id)
            .put("phone", id)
            .put("device", device)
            .put("model", model)
            .put("os", os)
            .toString()
    }

    fun heartbeatJson(): String = JSONObject().put("type", "heartbeat").toString()

    fun responseJson(action: String, payload: JSONObject): String {
        return JSONObject()
            .put("type", "response")
            .put("action", action)
            .put("payload", payload)
            .toString()
    }

    fun mouseClick(x: Double, y: Double, duration: Int = 80): JSONObject {
        return JSONObject()
            .put("type", MOUSE_CLICK)
            .put("x", x)
            .put("y", y)
            .put("duration", duration)
    }

    fun mouseSwipe(
        x: Double,
        y: Double,
        x2: Double,
        y2: Double,
        duration: Int = 180
    ): JSONObject {
        return JSONObject()
            .put("type", MOUSE_SWIPE)
            .put("x", x)
            .put("y", y)
            .put("x2", x2)
            .put("y2", y2)
            .put("duration", duration)
    }

    fun navKey(key: String): JSONObject {
        return JSONObject()
            .put("type", "press")
            .put("key", key)
    }

    fun encodeControl(json: String): ByteArray {
        return encodePacket(FLAG_CONTROL, json.toByteArray(Charsets.UTF_8))
    }

    fun videoChunkCount(jpegLength: Int): Int {
        if (jpegLength <= 0) return 0
        return (jpegLength + VIDEO_CHUNK_SIZE - 1) / VIDEO_CHUNK_SIZE
    }

    fun encodeVideoChunk(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        jpegLength: Int,
        chunkIndex: Int,
        frameId: Int
    ): ByteArray {
        val total = jpegLength.coerceIn(0, jpeg.size)
        val count = videoChunkCount(total)
        val offset = chunkIndex * VIDEO_CHUNK_SIZE
        val slice = min(VIDEO_CHUNK_SIZE, total - offset).coerceAtLeast(0)
        val payload = ByteArray(VIDEO_PREFIX_SIZE + slice)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            .putInt(width)
            .putInt(height)
            .putShort(chunkIndex.toShort())
            .putShort(count.toShort())
            .putInt(total)
            .putInt(frameId)
        if (slice > 0) {
            System.arraycopy(jpeg, offset, payload, VIDEO_PREFIX_SIZE, slice)
        }
        return encodePacket(FLAG_VIDEO, payload)
    }

    private fun encodePacket(flag: Byte, payload: ByteArray): ByteArray {
        val packet = ByteArray(PACKET_HEADER_SIZE + payload.size)
        ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            .put(flag)
            .putInt(payload.size)
            .put(payload)
        return packet
    }
}
