package com.bdtv.zapengine.sample

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.bdtv.zapengine.EngineId
import com.bdtv.zapengine.StreamRequest
import com.bdtv.zapengine.ZapEngineView

/**
 * Minimal demo: three public test streams, zapped with buttons (or D-Pad focus
 * + OK on a TV remote). The status line shows WHICH engine won the race and
 * how the start went — exactly what you want to see while testing.
 */
class MainActivity : Activity(), ZapEngineView.Listener {

    private lateinit var player: ZapEngineView
    private lateinit var status: TextView
    private var startTimeMs = 0L

    private val channels = listOf(
        StreamRequest(
            channelKey = "mux-test",
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        ),
        StreamRequest(
            channelKey = "apple-bipbop",
            url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
        ),
        StreamRequest(
            channelKey = "tears-of-steel",
            url = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ZapEngineView(this)
        player.listener = this

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            text = "Pick a channel — all 3 engines race, fastest frame wins"
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            channels.forEachIndexed { index, ch ->
                addView(Button(context).apply {
                    text = "Channel ${index + 1}"
                    setOnClickListener { playChannel(ch) }
                })
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(
                player,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            addView(status)
            addView(buttons)
        }
        setContentView(root)

        playChannel(channels[0])
    }

    private fun playChannel(req: StreamRequest) {
        startTimeMs = System.currentTimeMillis()
        player.play(req)
    }

    override fun onLoading(req: StreamRequest) {
        status.text = "Loading ${req.channelKey} — racing Exo vs VLC vs MPV…"
    }

    override fun onPlaying(req: StreamRequest, engine: EngineId) {
        val took = System.currentTimeMillis() - startTimeMs
        status.text = "▶ ${req.channelKey} — won by $engine in ${took} ms"
    }

    override fun onError(req: StreamRequest, errors: List<String>) {
        status.text = "✗ ${req.channelKey} failed on all engines:\n" + errors.joinToString("\n")
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
