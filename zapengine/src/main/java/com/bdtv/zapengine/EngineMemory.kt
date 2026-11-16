package com.bdtv.zapengine

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-channel memory of which engine + transport last played the channel.
 * The next open starts from the remembered route instead of guessing —
 * this is what makes repeat zaps near-instant.
 */
class EngineMemory(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Route(val engine: EngineId, val viaProxy: Boolean, val mpvSoftwareDecoder: Boolean)

    fun get(channelKey: String): Route? {
        val raw = prefs.getString(channelKey, null) ?: return null
        val parts = raw.split("|")
        if (parts.size < 2) return null
        val engine = runCatching { EngineId.valueOf(parts[0]) }.getOrNull() ?: return null
        return Route(
            engine = engine,
            viaProxy = parts[1] == "proxy",
            mpvSoftwareDecoder = parts.getOrNull(2) == "sw"
        )
    }

    fun put(channelKey: String, route: Route) {
        val transport = if (route.viaProxy) "proxy" else "direct"
        val decoder = if (route.mpvSoftwareDecoder) "sw" else "hw"
        prefs.edit().putString(channelKey, "${route.engine}|$transport|$decoder").apply()
    }

    fun clear(channelKey: String) {
        prefs.edit().remove(channelKey).apply()
    }

    companion object {
        private const val PREFS_NAME = "zapengine_memory_v1"
    }
}
