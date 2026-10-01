package com.screencapture.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object DeviceHashKey {

    /**
     * Read the Screen ID from /sdcard/ID_NUMBER. Empty when the file is
     * missing, unreadable, or does not contain a usable value.
     */
    fun readID(): String {
        val file = File(Environment.getExternalStorageDirectory().absolutePath, "ID_NUMBER")
        try {
            FileInputStream(file).use { f ->
                var u = 0
                val bytes = ByteArray(128)
                var i = 0
                while (u != -1) {
                    u = f.read()
                    if (i >= bytes.size) {
                        break
                    }
                    bytes[i] = u.toByte()
                    i++
                }
                if (i <= 1) {
                    return ""
                }
                return String(bytes, 0, i - 1, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            return ""
        }
    }

    fun sixDigitKey(context: Context): String {
        val source = buildSource(context)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))

        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xFF)
        }
        val sixDigits = (value and Long.MAX_VALUE) % 1_000_000L
        return sixDigits.toString().padStart(6, '0')
    }

    private fun buildSource(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else {
            ""
        }

        return listOf(
            androidId,
            manufacturer,
            model,
            device,
            hardware,
            soc
        ).joinToString("|")
    }
}
