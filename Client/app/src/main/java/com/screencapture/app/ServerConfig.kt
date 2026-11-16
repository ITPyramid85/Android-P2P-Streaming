package com.screencapture.app

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Server endpoint and device ID. The endpoint defaults to the cloud relay but can
 * be overridden by dropping a one-line file at Downloads/rc_relay.cfg with either
 * "host" or "host:port" (e.g. "192.168.0.20:9000"). That lets you run rc-relay.exe
 * on the same PC / LAN and point the phone at it, so traffic skips the internet
 * round-trip — the practical way to get a near-direct, low-latency link on a LAN
 * or LDPlayer. Remote setups just leave the file absent and keep the cloud relay.
 * Screen ID is read from /sdcard/ID_NUMBER when that file holds a 6-digit
 * value; otherwise it is a 6-digit device hash. Battery-prompt flag stays
 * in device-protected storage.
 */
object ServerConfig {
    const val DEFAULT_HOST = "167.114.172.44"
    const val DEFAULT_PORT = 9000
    private const val RELAY_CFG_NAME = "rc_relay.cfg"

    private const val PREFS = "server_config"
    private const val KEY_BATTERY_PROMPT = "battery_prompt_shown"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_SCREEN_ID = "screen_id_v2"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_STOPPED_BY_HOST = "stopped_by_host"
    private const val KEY_SETUP_DONE = "setup_done"
    private val SCREEN_ID_PATTERN = Regex("\\d{1,16}")

    @Volatile
    private var cachedScreenId: String? = null

    @Volatile
    private var cachedDeviceName: String? = null

    @Volatile
    private var cachedHost: String? = null

    @Volatile
    private var cachedPort: Int = 0

    private fun deviceContext(context: Context): Context {
        val app = context.applicationContext
        return try {
            app.createDeviceProtectedStorageContext()
        } catch (_: Throwable) {
            app
        }
    }

    private fun prefs(context: Context): android.content.SharedPreferences {
        return deviceContext(context.applicationContext)
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isConfigured(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    /**
     * Set when the default app broadcasts ACTION_CLOSE. Kick / watchdog / boot
     * paths honor this so accessibility rebinding cannot immediately revive the
     * session. Cleared when MainActivity is opened again.
     */
    fun isStoppedByHost(context: Context): Boolean =
        try {
            prefs(context).getBoolean(KEY_STOPPED_BY_HOST, false)
        } catch (_: Throwable) {
            false
        }

    fun setStoppedByHost(context: Context, stopped: Boolean) {
        try {
            prefs(context).edit().putBoolean(KEY_STOPPED_BY_HOST, stopped).commit()
        } catch (_: Throwable) {
        }
    }

    /**
     * False until the user has been walked through screen capture and accessibility
     * once. Only gates the guided first run — later launches check the permissions
     * themselves, because a MediaProjection token dies with the process and
     * accessibility can be switched off in Settings at any time.
     */
    fun isSetupComplete(context: Context): Boolean =
        try {
            prefs(context).getBoolean(KEY_SETUP_DONE, false)
        } catch (_: Throwable) {
            false
        }

    fun markSetupComplete(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_SETUP_DONE, true).commit()
        } catch (_: Throwable) {
        }
    }

    /**
     * Routing id the relay uses to key this agent. The 6-digit hash is unique per
     * install on a given device (install time differs). If the hash cannot be
     * built, fall back to model-plus-suffix so the device still appears.
     */
    fun clientId(context: Context): String {
        screenId(context)?.let { return it }
        val base = deviceModelId()
        val suffix = installId(context)
        return "$base-$suffix".take(64)
    }

    /**
     * Screen ID. Prefer an id the default app sent as the SCREEN_ID extra
     * (already cached / stored by [applyExternalScreenId]), then
     * [DeviceHashKey.readID] from /sdcard/ID_NUMBER when that file contains
     * exactly six digits, then the last stored value, then
     * [DeviceHashKey.sixDigitKey]. Null only if none of those work, in which
     * case [clientId] falls back to the original id.
     */
    fun screenId(context: Context): String? {
        cachedScreenId?.let { return it }
        val app = context.applicationContext
        val fromFile = DeviceHashKey.readID().trim().takeIf { it.matches(SCREEN_ID_PATTERN) }
        val stored = try {
            prefs(app).getString(KEY_SCREEN_ID, null)?.takeIf { it.matches(SCREEN_ID_PATTERN) }
        } catch (_: Throwable) {
            null
        }
        val resolved = fromFile ?: stored ?: try {
            DeviceHashKey.sixDigitKey(app)
        } catch (_: Throwable) {
            null
        } ?: return null
        persistScreenId(app, resolved)
        return resolved
    }

    /**
     * Take a SCREEN_ID extra from the default-app launch intent. Overwrites the
     * cached / stored id so the next register uses this value. False when the
     * extra is missing or not a usable id.
     */
    fun applyExternalScreenId(context: Context, raw: String): Boolean {
        val clean = sanitizeScreenId(raw) ?: return false
        persistScreenId(context.applicationContext, clean)
        return true
    }

    fun sanitizeScreenId(raw: String): String? {
        val clean = raw.trim()
        return clean.takeIf { it.matches(SCREEN_ID_PATTERN) }
    }

    private fun persistScreenId(context: Context, id: String) {
        cachedScreenId = id
        try {
            prefs(context).edit().putString(KEY_SCREEN_ID, id).commit()
        } catch (_: Throwable) {
        }
    }

    /**
     * The name the operator typed for this device, or null while it has never been
     * asked. Kept in device-protected storage so it is readable during direct boot,
     * before the user unlocks — the same reason the screen id lives there.
     */
    fun deviceName(context: Context): String? {
        cachedDeviceName?.let { return it }
        val stored = try {
            prefs(context).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        cachedDeviceName = stored
        return stored
    }

    /**
     * Store the typed name. Written with commit(), not apply(): the very next thing
     * that happens is a re-register, and on a device that is killed moments later an
     * unflushed name would mean the prompt comes back on the next start.
     */
    fun setDeviceName(context: Context, name: String) {
        val clean = sanitizeDeviceName(name) ?: return
        cachedDeviceName = clean
        try {
            prefs(context).edit().putString(KEY_DEVICE_NAME, clean).commit()
        } catch (_: Throwable) {
        }
    }

    /**
     * The desktop shows this in a table cell and the relay packs it into a fixed-size
     * JSON buffer, so drop control characters and cap the length.
     */
    fun sanitizeDeviceName(raw: String): String? {
        val clean = raw.trim()
            .filter { it.code >= 0x20 && it != '"' && it != '\\' }
            .trim()
            .take(48)
        return clean.ifBlank { null }
    }

    /**
     * A random id generated once per install and persisted in device-protected
     * storage (readable during direct boot, before the user unlocks). Survives
     * reboots but differs between two separate installs, so freshly installed
     * clones each get their own.
     */
    private fun installId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }

    fun host(@Suppress("UNUSED_PARAMETER") context: Context): String {
        ensureRelayLoaded()
        return cachedHost ?: DEFAULT_HOST
    }

    fun port(@Suppress("UNUSED_PARAMETER") context: Context): Int {
        ensureRelayLoaded()
        return if (cachedPort > 0) cachedPort else DEFAULT_PORT
    }

    private fun ensureRelayLoaded() {
        if (cachedHost != null) {
            return
        }
        var host = DEFAULT_HOST
        var port = DEFAULT_PORT
        try {
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val cfg = File(downloads, RELAY_CFG_NAME)
            if (cfg.isFile) {
                val line = cfg.readLines().firstOrNull { it.isNotBlank() }?.trim()
                if (!line.isNullOrEmpty()) {
                    val colon = line.lastIndexOf(':')
                    if (colon in 1 until line.length - 1) {
                        val p = line.substring(colon + 1).trim().toIntOrNull()
                        if (p != null && p in 1..65535) {
                            host = line.substring(0, colon).trim()
                            port = p
                        } else {
                            host = line
                        }
                    } else {
                        host = line
                    }
                }
            }
        } catch (_: Throwable) {
        }
        cachedHost = host
        cachedPort = port
    }

    fun deviceModelId(): String {
        val model = Build.MODEL.trim()
        val device = Build.DEVICE.trim()
        val id = when {
            model.isNotBlank() -> model
            device.isNotBlank() -> device
            else -> "Android"
        }
        return id.take(64)
    }

    fun batteryPromptShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_PROMPT, false)

    fun markBatteryPromptShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_BATTERY_PROMPT, true).apply()
    }
}
