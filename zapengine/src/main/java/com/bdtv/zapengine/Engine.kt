package com.bdtv.zapengine

import android.view.View

/** The three playback engines that race inside [ZapEngineView]. */
enum class EngineId { EXO, VLC, MPV }

/**
 * One channel to play.
 *
 * @param channelKey stable id of the channel (used for engine memory).
 * @param url main stream URL (direct).
 * @param backupUrl backup stream URL (direct), or null.
 * @param proxyUrl the SAME channel routed through the host app's local proxy
 *                 (e.g. BDTv's LocalStreamProxy), or null if the host has none.
 * @param proxyBackupUrl backup URL through the local proxy, or null.
 * @param userAgent HTTP user-agent to send, or null for the engine default.
 * @param referer HTTP referer to send (alixbd channels need theirs), or null.
 * @param preferredEngine host hint for where the chain should start
 *                 (e.g. VLC for alixbd URLs). Engine memory overrides this.
 */
data class StreamRequest(
    val channelKey: String,
    val url: String,
    val backupUrl: String? = null,
    val proxyUrl: String? = null,
    val proxyBackupUrl: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val preferredEngine: EngineId? = null
)

/** Callbacks every engine reports through. Calls may arrive on ANY thread (each engine has its own event thread); [ZapEngineView] marshals them to the main thread before touching race state. */
interface EngineCallback {
    /** This engine rendered its first frame for generation [gen]. */
    fun onFirstFrame(gen: Long, engine: EngineId)

    /** This engine failed to start generation [gen]; [error] is its real error text. */
    fun onStartError(gen: Long, engine: EngineId, error: String)

    /** The WINNING engine failed mid-playback. */
    fun onPlaybackError(gen: Long, engine: EngineId, error: String)
}

/**
 * One playback engine behind a uniform interface.
 * Implementations: [ExoEngine], [VlcEngine], [MpvEngine].
 */
interface Engine {
    val id: EngineId

    /** The engine's video view. Added once to the host layout; never removed. */
    val view: View

    /** Create the native player and attach [view]. Called once. */
    fun prepare(callback: EngineCallback)

    /** Start loading [url] for generation [gen]. Must be safe to call while a previous load is running. */
    fun load(gen: Long, url: String, req: StreamRequest)

    /** Stop playback and clear the picture. The view itself stays VISIBLE (surface rule). */
    fun stop()

    /** Pause playback, keeping the current load alive; [resume] continues it. */
    fun pause()

    /** Resume playback after [pause]. */
    fun resume()

    /** Set output volume, 0.0 (mute) .. 1.0 (full). */
    fun setVolume(volume: Float)

    /** Free the native player. Called once, when the host is destroyed. */
    fun release()
}
