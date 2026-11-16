package com.bdtv.zapengine

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ONE player, THREE engines, working AT THE SAME TIME.
 *
 * On [play], the channel is started in ExoPlayer, libVLC and libmpv
 * SIMULTANEOUSLY (a race). Whichever engine renders the first frame wins the
 * screen; the other two are stopped in the same instant. The viewer never
 * sees engines — only a channel that starts at the speed of the FASTEST
 * engine for that source, with the coverage of all three combined:
 *
 *   ExoPlayer — fastest start, plays most sources
 *   libVLC    — plays nearly everything (alixbd etc.)
 *   libmpv    — slowest to start, most stable once playing
 *
 * If every racer fails on the main URL, the race repeats on the backup URL,
 * then on the local-proxy URL, then the proxy backup — then onError.
 *
 * Engine memory: the winning engine+transport is remembered per channel.
 * The next open starts with the remembered engine alone (near-instant, one
 * connection); if it has not framed within [MEMORY_ESCALATION_MS], the other
 * two join the race automatically.
 *
 * Surface rule (learned the hard way in BDTv v20): an engine's view is made
 * VISIBLE before its load starts, and is hidden only AFTER that engine has
 * been fully stopped. The loading/playing engine's view is never hidden.
 *
 * UI-thread rule (1.0.8): libVLC and libmpv calls that can block (load,
 * stop, release) NEVER run on the main thread — each engine has a
 * dedicated background queue inside this view. Only ExoPlayer (a
 * non-blocking, main-thread-bound API) and view visibility changes touch
 * the UI thread, so the host app's drawer and animations stay smooth
 * while channels switch. Before this fix, a channel tap ran VLC's
 * blocking stop() on the UI thread: the whole app froze (drawer stuck),
 * then the channel suddenly played once the thread came back.
 */
class ZapEngineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), EngineCallback {

    interface Listener {
        /** A channel started (first frame on screen). [engine] won the race. */
        fun onPlaying(req: StreamRequest, engine: EngineId)

        /** A new channel was requested and loading has begun. */
        fun onLoading(req: StreamRequest)

        /** Every engine and every route failed. [errors] holds each engine's real error text. */
        fun onError(req: StreamRequest, errors: List<String>)
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private val memory = EngineMemory(context)

    private val exo = ExoEngine(context)
    private val vlc = VlcEngine(context)
    private val mpv = MpvEngine(context)
    private val engines: Map<EngineId, Engine> =
        mapOf(EngineId.EXO to exo, EngineId.VLC to vlc, EngineId.MPV to mpv)

    private var prepared = false

    // Background work queues for the two native engines (1.0.8): every
    // load/stop/release for libVLC and libmpv runs on the engine's own
    // single thread, FIFO-ordered, so the UI thread NEVER waits on a
    // native player.
    private var vlcQueue: ExecutorService? = null
    private var mpvQueue: ExecutorService? = null

    /**
     * Plain black cover shown the INSTANT a new channel is requested, so
     * the previous channel's frozen last frame never sits on screen while
     * the next one loads (the classic "tap holds the old picture" defect).
     * It is a normal View — hiding/showing it never touches any engine's
     * SurfaceView, so the surface rule is untouched. The winner's view is
     * lifted above it at first frame and it is hidden in the same instant.
     */
    private val cover = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = GONE
    }

    /**
     * The HOST app's own loading view (e.g. BDTv's TV animation), handed
     * in via [setLoadingView]. When set, it replaces the black cover as
     * the loading surface: shown the instant a channel is requested —
     * never the previous channel's frozen frame — and hidden the moment
     * the new first frame lands. It lives as this view's topmost child,
     * so it is always ABOVE the engine surfaces and always BELOW
     * everything outside the player (the app's drawer included). The
     * library only reparents it and toggles its visibility; its content
     * and design are the host's and are never touched.
     */
    private var loadingView: View? = null

    // ---- race state (main thread only) ----
    private var generation: Long = 0
    private var currentReq: StreamRequest? = null
    private var step: Step = Step.MAIN
    private var winner: EngineId? = null
    private var recoveryUsed = false
    private val started = mutableSetOf<EngineId>()
    private val startErrors = mutableMapOf<EngineId, String>()
    private val allErrors = mutableListOf<String>()

    private enum class Step { MAIN, BACKUP, PROXY, PROXY_BACKUP }

    init {
        // All three surfaces live in this layout for the view's whole life.
        // Stacking order is managed with bringToFront() when an engine wins.
        setBackgroundColor(Color.BLACK)
        addView(mpv.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(vlc.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(exo.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ API

    /** Play a channel. Any race already running is cancelled instantly. */
    fun play(req: StreamRequest) {
        ensurePrepared()
        val gen = ++generation
        currentReq = req
        winner = null
        userPaused = false // a fresh channel always starts playing
        recoveryUsed = false
        allErrors.clear()
        stopAllEngines()
        listener?.onLoading(req)
        startStep(gen, Step.MAIN)
        log("gen=$gen play ${req.channelKey} step=MAIN")
    }

    /** Stop everything and go idle. */
    fun stopPlayback() {
        generation++
        userPaused = false
        hideLoadingSurface()
        cancelTimers()
        stopAllEngines()
        winner = null
        currentReq = null
    }

    /**
     * Hand the player the host app's OWN loading view (its existing
     * loading animation). The view is reparented as this player's
     * topmost child and from then on the library owns its visibility:
     * shown at every channel request, hidden at first frame, at final
     * error, and on [stopPlayback]. Pass null to go back to the plain
     * black cover. The view's content/design is never modified.
     */
    fun setLoadingView(view: View?) {
        loadingView?.let { if (it.parent == this) removeView(it) }
        loadingView = view
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            view.visibility = GONE
        }
    }

    /** The engine currently on screen, or null. */
    val activeEngine: EngineId? get() = winner

    // ------------------------------------------------------- transport API

    @Volatile private var userPaused = false
    @Volatile private var volumeLevel = 1f
    private var preMuteVolume = 1f

    /** True while playback is held by [pause]. */
    val isPaused: Boolean get() = userPaused

    /** True when a channel is on screen and not paused. */
    val isPlaying: Boolean get() = winner != null && !userPaused

    /**
     * Pause the picture. Every engine is paused (the winner freezes on
     * screen); a race still in progress stays loaded and paused.
     */
    fun pause() {
        userPaused = true
        engines.values.forEach { runCatching { it.pause() } }
    }

    /** Resume after [pause]. */
    fun resume() {
        userPaused = false
        engines.values.forEach { runCatching { it.resume() } }
    }

    /**
     * Set output volume on every engine, 0.0 (mute) .. 1.0 (full).
     * The level is remembered and applied to every future load as well.
     */
    fun setVolume(volume: Float) {
        volumeLevel = volume.coerceIn(0f, 1f)
        engines.values.forEach { runCatching { it.setVolume(volumeLevel) } }
    }

    /** Mute / unmute. Unmute restores the level from before the mute. */
    fun setMuted(muted: Boolean) {
        if (muted) {
            if (volumeLevel > 0f) preMuteVolume = volumeLevel
            setVolume(0f)
        } else {
            setVolume(preMuteVolume)
        }
    }

    /**
     * DNS-over-HTTPS (Google Public DNS) for ExoPlayer loads. Default OFF —
     * every engine then uses the Android system DNS, exactly as before.
     * Honest limit: libVLC and libmpv resolve names inside their native
     * stacks and expose no override, so this applies to ExoPlayer only.
     */
    fun setDnsOverHttps(enabled: Boolean) {
        exo.dnsOverHttps = enabled
    }

    /** Whether DNS-over-HTTPS is currently enabled (ExoPlayer loads only). */
    val dnsOverHttpsEnabled: Boolean get() = exo.dnsOverHttps

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Pre-warm all three engines at attach time (parent attaches before
        // its children, so the MPV view's surface does not exist yet and its
        // initialize() runs in the library's intended order).
        ensurePrepared()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    fun release() {
        stopPlayback()
        if (prepared) {
            engines.forEach { (id, engine) ->
                val queue = queueFor(id)
                if (queue == null) runCatching { engine.release() }
                else queue.execute { runCatching { engine.release() } }
            }
            vlcQueue?.shutdown(); vlcQueue = null
            mpvQueue?.shutdown(); mpvQueue = null
            prepared = false
        }
    }

    // ------------------------------------------------------------- race core

    private fun ensurePrepared() {
        if (prepared) return
        if (vlcQueue?.isShutdown != false) {
            vlcQueue = Executors.newSingleThreadExecutor { r ->
                Thread(r, "ZapEngine-VLC").apply { isDaemon = true }
            }
        }
        if (mpvQueue?.isShutdown != false) {
            mpvQueue = Executors.newSingleThreadExecutor { r ->
                Thread(r, "ZapEngine-MPV").apply { isDaemon = true }
            }
        }
        engines.values.forEach { it.prepare(this) }
        prepared = true
    }

    private fun startStep(gen: Long, newStep: Step) {
        val req = currentReq ?: return
        step = newStep
        started.clear()
        startErrors.clear()
        // The old picture dies NOW, not when the new one arrives.
        showLoadingSurface()

        val remembered = memory.get(req.channelKey)
        // The remembered route went through the proxy — begin there directly
        // instead of wasting seconds on a direct attempt that already failed.
        if (newStep == Step.MAIN && remembered?.viaProxy == true) {
            startStep(gen, Step.PROXY)
            return
        }

        val url = urlFor(req, newStep)
        if (url == null) { // this route does not exist for this channel — skip it
            advanceStep(gen)
            return
        }

        val solo: EngineId? = when (newStep) {
            Step.MAIN -> remembered?.engine ?: req.preferredEngine
            Step.PROXY -> if (remembered?.viaProxy == true) remembered.engine else null
            else -> null
        }

        if (solo != null) {
            launchEngine(gen, solo, url, req)
            // If the known-good engine has not framed quickly, everyone joins.
            main.postDelayed({ escalate(gen) }, MEMORY_ESCALATION_MS)
        } else {
            // THE RACE: all three engines start the same URL at the same time.
            EngineId.entries.forEach { launchEngine(gen, it, url, req) }
        }
        main.postDelayed({ onStepDeadline(gen) }, START_DEADLINE_MS)
    }

    private fun escalate(gen: Long) {
        if (gen != generation || winner != null) return
        val req = currentReq ?: return
        val url = urlFor(req, step) ?: return
        EngineId.entries
            .filter { it !in started }
            .forEach { launchEngine(gen, it, url, req) }
        log("gen=$gen escalated to full race step=$step")
    }

    private fun launchEngine(gen: Long, engineId: EngineId, url: String, req: StreamRequest) {
        if (engineId in started) return // never launch the same engine twice in one step
        val engine = engines.getValue(engineId)
        engine.view.visibility = VISIBLE // surface rule: visible BEFORE load
        started += engineId
        val queue = queueFor(engineId)
        if (queue == null) {
            // ExoPlayer: non-blocking API, bound to the main thread — inline.
            runCatching { engine.load(gen, url, req) }
                .onFailure { onStartError(gen, engineId, "${engineId.name} load threw: ${it.message}") }
                .onSuccess { applyTransport(engine) }
        } else {
            // libVLC / libmpv: load() can BLOCK (VLC stops the previous
            // stream inside load; both engines wind native threads down).
            // It runs on the engine's own background queue, FIFO-ordered
            // after any stop submitted earlier, so a channel tap never
            // freezes the UI thread.
            queue.execute {
                runCatching { engine.load(gen, url, req) }
                    .onFailure { onStartError(gen, engineId, "${engineId.name} load threw: ${it.message}") }
                    .onSuccess { applyTransport(engine) }
            }
        }
    }

    /** Honour the transport state on every fresh load (volume level, mid-race pause). */
    private fun applyTransport(engine: Engine) {
        runCatching { engine.setVolume(volumeLevel) }
        if (userPaused) runCatching { engine.pause() }
    }

    private fun onStepDeadline(gen: Long) {
        if (gen != generation || winner != null) return
        // Nobody framed in time. Record timeouts for engines still trying and move on.
        started.filter { it !in startErrors }
            .forEach { startErrors[it] = "${it.name}: no first frame within ${START_DEADLINE_MS / 1000}s" }
        advanceStep(gen)
    }

    private fun advanceStep(gen: Long) {
        if (gen != generation) return
        cancelTimers()
        allErrors += startErrors.values
        stopAllEngines()
        val next = when (step) {
            Step.MAIN -> Step.BACKUP
            Step.BACKUP -> Step.PROXY
            Step.PROXY -> Step.PROXY_BACKUP
            Step.PROXY_BACKUP -> null
        }
        if (next == null) {
            val req = currentReq ?: return
            hideLoadingSurface()
            log("gen=$gen ALL ROUTES FAILED: $allErrors")
            listener?.onError(req, allErrors.toList())
        } else {
            log("gen=$gen advancing to step=$next")
            startStep(gen, next)
        }
    }

    private fun checkStepExhausted(gen: Long) {
        if (gen != generation || winner != null) return
        if (started.isNotEmpty() && startErrors.keys.containsAll(started)) {
            // Every engine that started has failed, and no escalation is pending
            // that could still add a racer: if some engines never started, the
            // escalation timer will launch them; only advance when all 3 are in.
            if (started.size == EngineId.entries.size) advanceStep(gen)
        }
    }

    // -------------------------------------------------------- engine events
    //
    // Engine callbacks arrive on each engine's OWN thread (VLC/MPV native
    // event threads, Exo's playback thread). ALL race state lives on the
    // main thread, so every callback is marshalled there first — this is
    // what keeps rapid channel zapping free of cross-thread races.

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    override fun onFirstFrame(gen: Long, engine: EngineId) {
        onMain { handleFirstFrame(gen, engine) }
    }

    override fun onStartError(gen: Long, engine: EngineId, error: String) {
        onMain { handleStartError(gen, engine, error) }
    }

    override fun onPlaybackError(gen: Long, engine: EngineId, error: String) {
        onMain { handlePlaybackError(gen, engine, error) }
    }

    private fun handleFirstFrame(gen: Long, engine: EngineId) {
        if (gen != generation || winner != null) return // stale, or another engine already won
        val req = currentReq ?: return
        winner = engine
        cancelTimers()
        // An engine can unpause itself internally (e.g. MPV's decoder retry)
        // — re-apply a host-requested pause to the winner.
        if (userPaused) runCatching { engines.getValue(engine).pause() }

        // Winner takes the screen; losers stop in the same instant.
        engines.getValue(engine).view.bringToFront()
        hideLoadingSurface() // winner is on screen now; old picture already gone
        engines.forEach { (otherId, _) ->
            if (otherId != engine) stopEngineAsync(otherId, hideAfter = true)
        }

        val viaProxy = step == Step.PROXY || step == Step.PROXY_BACKUP
        memory.put(
            req.channelKey,
            EngineMemory.Route(
                engine = engine,
                viaProxy = viaProxy,
                mpvSoftwareDecoder = if (engine == EngineId.MPV) mpv.usedSoftwareDecoder else false
            )
        )
        log("gen=$gen WINNER=$engine step=$step channel=${req.channelKey}")
        listener?.onPlaying(req, engine)
    }

    private fun handleStartError(gen: Long, engine: EngineId, error: String) {
        if (gen != generation || winner != null) return
        startErrors[engine] = error
        log("gen=$gen startError $engine step=$step: $error")
        // If the solo (remembered/preferred) engine failed, escalate right now
        // instead of waiting for the escalation timer.
        if (started.size < EngineId.entries.size) escalate(gen)
        checkStepExhausted(gen)
    }

    private fun handlePlaybackError(gen: Long, engine: EngineId, error: String) {
        if (gen != generation || winner != engine) return
        val req = currentReq ?: return
        allErrors += "$engine mid-play: $error"
        log("gen=$gen playbackError $engine: $error")
        if (recoveryUsed) {
            listener?.onError(req, allErrors.toList())
            return
        }
        recoveryUsed = true
        winner = null
        memory.clear(req.channelKey) // the remembered route just went bad
        // One recovery: advance the chain and race everything again.
        advanceStep(gen)
    }

    // -------------------------------------------------------------- helpers

    /** Show the host's loading view if one was set, else the black cover. */
    private fun showLoadingSurface() {
        val lv = loadingView
        if (lv != null) {
            cover.visibility = GONE
            lv.visibility = VISIBLE
            lv.bringToFront()
        } else {
            cover.visibility = VISIBLE
            cover.bringToFront()
        }
    }

    private fun hideLoadingSurface() {
        cover.visibility = GONE
        loadingView?.visibility = GONE
    }

    private fun urlFor(req: StreamRequest, s: Step): String? = when (s) {
        Step.MAIN -> req.url
        Step.BACKUP -> req.backupUrl
        Step.PROXY -> req.proxyUrl
        Step.PROXY_BACKUP -> req.proxyBackupUrl
    }

    private fun stopAllEngines() {
        engines.forEach { (id, engine) ->
            val queue = queueFor(id)
            if (queue == null) runCatching { engine.stop() }
            else queue.execute { runCatching { engine.stop() } }
        }
    }

    /**
     * Stop one engine WITHOUT blocking the UI thread. ExoPlayer stops
     * inline (its API is non-blocking). libVLC/libmpv stops run on the
     * engine's own background queue; with [hideAfter], the engine's view
     * is hidden only once the stop has actually finished — and only if
     * the engine has not been re-launched meanwhile (the guard is checked
     * on the main thread, where all race state lives).
     */
    private fun stopEngineAsync(id: EngineId, hideAfter: Boolean) {
        val engine = engines.getValue(id)
        val queue = queueFor(id)
        if (queue == null) {
            runCatching { engine.stop() }
            if (hideAfter) engine.view.visibility = INVISIBLE
            return
        }
        queue.execute {
            runCatching { engine.stop() }
            if (hideAfter) {
                main.post {
                    if (id !in started && winner != id) engine.view.visibility = INVISIBLE
                }
            }
        }
    }

    /** Background queue for the native engines; ExoPlayer returns null (main thread). */
    private fun queueFor(id: EngineId): ExecutorService? = when (id) {
        EngineId.VLC -> vlcQueue
        EngineId.MPV -> mpvQueue
        EngineId.EXO -> null
    }

    private fun cancelTimers() {
        main.removeCallbacksAndMessages(null)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }

    companion object {
        private const val TAG = "ZapEngine"

        /** Solo (remembered/preferred) engine gets this long before the others join the race. */
        const val MEMORY_ESCALATION_MS = 1_500L

        /**
         * Per-route start deadline. In a race the FASTEST engine wins, so this
         * only bites when every engine is slow — 20s gives even MPV a fair
         * window before the backup/proxy route is tried instead.
         */
        const val START_DEADLINE_MS = 20_000L
    }
}
