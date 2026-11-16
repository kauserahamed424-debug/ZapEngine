package com.bdtv.zapengine

import android.content.Context
import android.util.AttributeSet
import android.view.View
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.io.File

/**
 * libmpv via mpv-android-lib 0.2.1 — the STABILITY engine: once a channel is
 * playing it barely buffers, but it fails more sources than VLC/Exo, so in
 * the race it is a full participant whose wins are valued for long viewing.
 *
 * API NOTE (verified 2026-11-16 against the published 0.2.1 android sources):
 * 0.2.x is a Kotlin-multiplatform rewrite. There is NO static `MPVLib` class
 * (that was 0.1.x). Everything goes through the [MPV] instance owned by
 * [BaseMPVView] (`view.mpv`), observers are Kotlin interfaces implemented
 * with `object :`, and the player starts PAUSED (its init sets pause=true),
 * so every load must explicitly unpause.
 *
 * Locked settings from the BDTv v29/v31 builds:
 *  - TLS fixed with a bundled Mozilla cacert.pem (MPV cannot see Android's CA store)
 *  - demuxer-lavf-o carries ONLY live_start_index=-1 (start at the live edge)
 *  - probe tuning is BANNED (probesize/analyzeduration stay default)
 *  - hwdec=mediacodec-copy with ONE internal software-decoder retry per load
 *  - panscan=1.0 (picture fills the screen), keep-open=yes
 */
class MpvEngine(private val context: Context) : Engine {

    override val id = EngineId.MPV

    private lateinit var callback: EngineCallback
    private var currentGen: Long = -1
    private var frameReported = false
    private var softwareRetried = false

    /** True when the current/last successful load is using the software decoder. */
    @Volatile var usedSoftwareDecoder: Boolean = false
        private set

    @Volatile private var lastErrorLine: String = ""
    private var lastUrl: String? = null

    private val mpvView = ZapMpvView(context)

    override val view: View get() = mpvView

    private val mpv: MPV get() = mpvView.mpv

    private val eventObserver = object : MPV.EventObserver {
        override fun event(eventId: Int, data: MPVNode) {
            val gen = currentGen
            if (gen < 0) return
            if (eventId == MPV.mpvEvent.MPV_EVENT_END_FILE) {
                if (!frameReported) {
                    if (!softwareRetried) {
                        // One internal retry with the software decoder (v29 rule).
                        softwareRetried = true
                        usedSoftwareDecoder = true
                        mpv.setPropertyString("hwdec", "no")
                        lastUrl?.let {
                            mpvView.playFile(it)
                            mpv.setPropertyBoolean("pause", false)
                        }
                    } else {
                        callback.onStartError(
                            gen, id,
                            "MPV: ${lastErrorLine.ifBlank { "stream ended before first frame" }}"
                        )
                    }
                } else {
                    callback.onPlaybackError(gen, id, "MPV: stream ended")
                }
            }
        }

        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: String) {}
        override fun eventProperty(property: String, value: Double) {}
        override fun eventProperty(property: String, value: MPVNode) {}
        override fun eventProperty(property: String, value: Boolean) {
            if (property == "vo-configured" && value) {
                val gen = currentGen
                if (gen >= 0 && !frameReported) {
                    frameReported = true
                    callback.onFirstFrame(gen, id)
                }
            }
        }
    }

    private val logObserver = object : MPV.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            // Keep the last real error line so a failure can be diagnosed by
            // name, not guessed (v28 logging rule).
            if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR ||
                text.contains("error", ignoreCase = true) ||
                text.contains("failed", ignoreCase = true)
            ) {
                lastErrorLine = text.trim()
            }
        }
    }

    override fun prepare(callback: EngineCallback) {
        this.callback = callback
        // Extract the bundled CA bundle once — MPV's TLS stack cannot read
        // Android's system CA store, so https fails without this (v25 fix).
        val caFile = File(context.filesDir, "cacert.pem")
        if (!caFile.exists()) {
            context.assets.open("cacert.pem").use { input ->
                caFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        mpvView.caFilePath = caFile.absolutePath
        mpvView.initialize(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath
        )
        mpv.addObserver(eventObserver)
        mpv.addLogObserver(logObserver)
    }

    override fun load(gen: Long, url: String, req: StreamRequest) {
        currentGen = gen
        frameReported = false
        softwareRetried = false
        usedSoftwareDecoder = false
        lastErrorLine = ""
        lastUrl = url

        mpv.setPropertyString("hwdec", "mediacodec-copy")
        mpv.setPropertyString("user-agent", req.userAgent ?: ExoEngine.DEFAULT_UA)
        val headers = mutableListOf<String>()
        req.referer?.let { headers += "Referer: $it" }
        mpv.setPropertyString("http-header-fields", headers.joinToString(","))
        // playFile defers to surfaceCreated if the surface is not ready yet.
        mpvView.playFile(url)
        // The 0.2.x library creates the player PAUSED — always unpause.
        mpv.setPropertyBoolean("pause", false)
    }

    override fun stop() {
        currentGen = -1
        frameReported = false
        runCatching { mpv.command("stop") }
    }

    override fun pause() {
        runCatching { mpv.setPropertyBoolean("pause", true) }
    }

    override fun resume() {
        runCatching { mpv.setPropertyBoolean("pause", false) }
    }

    override fun setVolume(volume: Float) {
        runCatching { mpv.setPropertyDouble("volume", volume.coerceIn(0f, 1f) * 100.0) }
    }

    override fun release() {
        stop()
        runCatching { mpv.removeObserver(eventObserver) }
        runCatching { mpv.removeLogObserver(logObserver) }
        runCatching { mpvView.destroy() }
    }
}

/** The MPV surface. Options split exactly as the library requires. */
class ZapMpvView(context: Context, attrs: AttributeSet? = null) :
    BaseMPVView(context, attrs) {

    var caFilePath: String = ""

    /** Playback options, applied by BaseMPVView.initialize() before use. */
    override fun initOptions() {
        // vo/ao are managed by BaseMPVView itself (surface lifecycle) — do not set them here.
        mpv.setOptionString("hwdec", "mediacodec-copy")
        // TLS (v25): bundled CA bundle + verification on.
        if (caFilePath.isNotEmpty()) {
            mpv.setOptionString("tls-ca-file", caFilePath)
            mpv.setOptionString("tls-verify", "yes")
        }
        mpv.setOptionString("cache-pause-initial", "no")
        mpv.setOptionString("network-timeout", "10")
        mpv.setOptionString("demuxer-readahead-secs", "30")
        mpv.setOptionString("cache-secs", "40")
        mpv.setOptionString("demuxer-max-bytes", "64MiB")
        mpv.setOptionString("cache-pause-wait", "1")
        // Live-edge start ONLY (v31). Probesize/analyzeduration stay default.
        mpv.setOptionString("demuxer-lavf-o", "live_start_index=-1")
        mpv.setOptionString(
            "stream-lavf-o",
            "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5"
        )
        mpv.setOptionString("panscan", "1.0")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("msg-level", "all=warn")
    }

    override fun observeProperties() {
        // First-frame signal used by the BDTv builds: vo-configured flips true
        // when the video output is configured and a frame is on its way out.
        mpv.observeProperty("vo-configured", MPV.mpvFormat.MPV_FORMAT_FLAG)
    }
}
