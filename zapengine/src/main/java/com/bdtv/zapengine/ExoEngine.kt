package com.bdtv.zapengine

import android.content.Context
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * ExoPlayer (Media3) — the FAST engine.
 * Locked tuning from the BDTv dual-engine builds: LoadControl 15000/40000/1200/4000,
 * cookie support via a java.net CookieManager (DefaultHttpDataSource uses
 * HttpURLConnection, which honours the default CookieHandler), stretch-to-fill (FILL: no bars, no crop).
 */
@UnstableApi
class ExoEngine(private val context: Context) : Engine {

    override val id = EngineId.EXO

    private lateinit var callback: EngineCallback
    private var player: ExoPlayer? = null
    private var currentGen: Long = -1
    private var frameReported = false

    /** One cookie store, shared by BOTH HTTP stacks (see [load]). */
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    /**
     * DNS-over-HTTPS switch, set by [ZapEngineView.setDnsOverHttps].
     * When ON, loads go through OkHttp resolving via Google Public DNS
     * over HTTPS; when OFF (default), the classic HttpURLConnection stack
     * and the Android system DNS are used — the proven BDTv behaviour.
     */
    @Volatile var dnsOverHttps: Boolean = false
    private var dohClient: OkHttpClient? = null

    private val playerView = PlayerView(context).apply {
        useController = false
        // House rule (all three engines): STRETCH the picture to exactly the
        // surface size — no side bars, and nothing is ever cropped off the
        // top/bottom (channel logos and score text stay fully visible).
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    override val view: View get() = playerView

    override fun prepare(callback: EngineCallback) {
        this.callback = callback
        // One cookie store for the whole app process (alixbd-type sources set cookies).
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(cookieManager)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_200, 4_000)
            .build()
        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                playerView.player = exo
                // Captions/subtitles OFF, always (owner's rule): some
                // channels carry a DEFAULT=YES subtitle or CC track that
                // engines otherwise auto-select and render.
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                exo.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        val gen = currentGen
                        if (gen >= 0 && !frameReported) {
                            frameReported = true
                            callback.onFirstFrame(gen, id)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val gen = currentGen
                        if (gen < 0) return
                        val msg = error.cause?.message ?: error.message ?: error.errorCodeName
                        if (frameReported) callback.onPlaybackError(gen, id, "ExoPlayer: $msg")
                        else callback.onStartError(gen, id, "ExoPlayer: $msg")
                    }
                })
            }
    }

    override fun load(gen: Long, url: String, req: StreamRequest) {
        val exo = player ?: return
        currentGen = gen
        frameReported = false

        val headers = mutableMapOf<String, String>()
        req.referer?.let { headers["Referer"] = it }
        val ua = req.userAgent ?: DEFAULT_UA
        // Two HTTP stacks, one cookie store: default = HttpURLConnection
        // (system DNS); DoH mode = OkHttp + DNS-over-HTTPS.
        val factory: DataSource.Factory = if (dnsOverHttps) {
            OkHttpDataSource.Factory(dohClient()).apply {
                setUserAgent(ua)
                if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            }
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(10_000)
                .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }
        }

        val mediaItem = MediaItem.fromUri(url)
        val source = if (url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(factory).createMediaSource(mediaItem)
        } else {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory)
                .createMediaSource(mediaItem)
        }
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    /** OkHttp client resolving through Google Public DNS over HTTPS. Built once. */
    private fun dohClient(): OkHttpClient {
        dohClient?.let { return it }
        val dns = DnsOverHttps.Builder()
            .client(OkHttpClient())
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4"),
                InetAddress.getByName("2001:4860:4860::8888"),
                InetAddress.getByName("2001:4860:4860::8844")
            )
            .build()
        val client = OkHttpClient.Builder()
            .dns(dns)
            .cookieJar(JavaNetCookieJar(cookieManager))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        dohClient = client
        return client
    }

    override fun stop() {
        currentGen = -1
        frameReported = false
        player?.stop()
        player?.clearMediaItems()
    }

    override fun pause() {
        player?.pause()
    }

    override fun resume() {
        player?.play()
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    override fun release() {
        stop()
        playerView.player = null
        player?.release()
        player = null
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Smart TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
