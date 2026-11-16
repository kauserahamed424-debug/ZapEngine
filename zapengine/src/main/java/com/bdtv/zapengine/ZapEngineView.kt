package com.bdtv.zapengine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout

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
        addView(mpv.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(vlc.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(exo.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        cancelTimers()
        stopAllEngines()
        winner = null
        currentReq = null
    }

    /** The engine currently on screen, or null. */
    val activeEngine: EngineId? get() = winner

    // ------------------------------------------------------- transport API

    private var userPaused = false
    private var volumeLevel = 1f
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
            engines.values.forEach { runCatching { it.release() } }
            prepared = false
        }
    }

    // ------------------------------------------------------------- race core

    private fun ensurePrepared() {
        if (prepared) return
        engines.values.forEach { it.prepare(this) }
        prepared = true
    }

    private fun startStep(gen: Long, newStep: Step) {
        val req = currentReq ?: return
        step = newStep
        started.clear()
        startErrors.clear()

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
        runCatching { engine.load(gen, url, req) }
            .onFailure { onStartError(gen, engineId, "${engineId.name} load threw: ${it.message}") }
            .onSuccess {
                // Honour the transport state on every fresh load (volume
                // level, and a pause() the host requested mid-race).
                runCatching { engine.setVolume(volumeLevel) }
                if (userPaused) runCatching { engine.pause() }
            }
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

    override fun onFirstFrame(gen: Long, engine: EngineId) {
        if (gen != generation || winner != null) return // stale, or another engine already won
        val req = currentReq ?: return
        winner = engine
        cancelTimers()
        // An engine can unpause itself internally (e.g. MPV's decoder retry)
        // — re-apply a host-requested pause to the winner.
        if (userPaused) runCatching { engines.getValue(engine).pause() }

        // Winner takes the screen; losers stop in the same instant.
        engines.getValue(engine).view.bringToFront()
        engines.forEach { (otherId, other) ->
            if (otherId != engine) {
                runCatching { other.stop() }
                other.view.visibility = INVISIBLE // safe: engine fully stopped
            }
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

    override fun onStartError(gen: Long, engine: EngineId, error: String) {
        if (gen != generation || winner != null) return
        startErrors[engine] = error
        log("gen=$gen startError $engine step=$step: $error")
        // If the solo (remembered/preferred) engine failed, escalate right now
        // instead of waiting for the escalation timer.
        if (started.size < EngineId.entries.size) escalate(gen)
        checkStepExhausted(gen)
    }

    override fun onPlaybackError(gen: Long, engine: EngineId, error: String) {
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

    private fun urlFor(req: StreamRequest, s: Step): String? = when (s) {
        Step.MAIN -> req.url
        Step.BACKUP -> req.backupUrl
        Step.PROXY -> req.proxyUrl
        Step.PROXY_BACKUP -> req.proxyBackupUrl
    }

    private fun stopAllEngines() {
        engines.values.forEach { runCatching { it.stop() } }
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
