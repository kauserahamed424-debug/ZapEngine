package com.bdtv.zapengine

import android.content.Context
import android.net.Uri
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC 3.6.2 — the COVERAGE engine (plays nearly everything, incl. alixbd).
 * Locked tuning from the BDTv dual-engine builds: :network-caching=1500,
 * :http-reconnect, Chrome user-agent, per-media referer, stretch-to-fill.
 */
class VlcEngine(private val context: Context) : Engine {

    override val id = EngineId.VLC

    private lateinit var callback: EngineCallback
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentGen: Long = -1
    private var frameReported = false

    private val layout = VLCVideoLayout(context)

    override val view: View get() = layout

    override fun prepare(callback: EngineCallback) {
        this.callback = callback
        val vlc = LibVLC(context, arrayListOf("--no-stats", "--network-caching=1500"))
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        mediaPlayer = mp
        mp.attachViews(layout, null, false, false)
        // Re-apply the stretch-to-fill whenever the layout gets its real size:
        // load() usually runs before the first layout pass (w/h still 0),
        // so without this the fill call in load() silently does nothing.
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyFill() }
        mp.setEventListener { event ->
            val gen = currentGen
            if (gen < 0) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    // Captions/subtitles OFF, always (owner's rule): VLC
                    // otherwise auto-selects a stream's subtitle/CC track.
                    // Tracks only exist once playback starts, so this
                    // cannot be done in load().
                    runCatching { mp.setSpuTrack(-1) }
                    // Audio-only guard: after a stop/start cycle VLC can
                    // leave the VIDEO track deselected — audio plays, no
                    // picture, and NO error is reported. If no video track
                    // is selected, re-enable and select the first one.
                    runCatching {
                        if (mp.getVideoTrack() == -1) {
                            mp.setVideoTrackEnabled(true)
                            mp.videoTracks?.firstOrNull()?.let { mp.setVideoTrack(it.id) }
                        }
                    }
                    if (!frameReported) {
                        frameReported = true
                        callback.onFirstFrame(gen, id)
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    val msg = "VLC encountered an error"
                    if (frameReported) callback.onPlaybackError(gen, id, msg)
                    else callback.onStartError(gen, id, msg)
                }
                MediaPlayer.Event.EndReached -> {
                    if (frameReported) callback.onPlaybackError(gen, id, "VLC: stream ended")
                }
            }
        }
    }

    override fun load(gen: Long, url: String, req: StreamRequest) {
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        currentGen = gen
        frameReported = false
        mp.stop()

        val media = Media(vlc, Uri.parse(url))
        media.addOption(":network-caching=1500")
        media.addOption(":http-reconnect")
        media.addOption(":http-user-agent=${req.userAgent ?: ExoEngine.DEFAULT_UA}")
        req.referer?.let { media.addOption(":http-referrer=$it") }
        mp.media = media
        media.release()
        // Stretch-to-fill: picture takes the whole screen, no side bars, no crop.
        mp.setVideoTrackEnabled(true)
        mp.play()
        applyFill()
    }

    /** VLC stretch-to-fill; also re-applied automatically on every layout change. */
    fun applyFill() {
        val mp = mediaPlayer ?: return
        val w = layout.width
        val h = layout.height
        if (w > 0 && h > 0) {
            mp.setScale(0f) // use surface size
            mp.setAspectRatio("$w:$h")
        }
    }

    override fun stop() {
        currentGen = -1
        frameReported = false
        mediaPlayer?.stop()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun resume() {
        mediaPlayer?.play()
    }

    override fun setVolume(volume: Float) {
        mediaPlayer?.setVolume((volume.coerceIn(0f, 1f) * 100).toInt())
    }

    override fun release() {
        stop()
        mediaPlayer?.detachViews()
        mediaPlayer?.release()
        mediaPlayer = null
        libVlc?.release()
        libVlc = null
    }
}
