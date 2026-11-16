# ZapEngine

**One player. Three engines. Racing.**

ZapEngine is an Android media player library that runs **ExoPlayer (Media3),
libVLC and libmpv at the same time** — on every channel open, all three
engines start the same stream simultaneously, and the engine that renders
the **first frame wins the screen**. The losers are stopped in the same
instant.

> Created by **Kauser Ahamed** — the playback engine behind the BDTv app.

[![JitPack](https://jitpack.io/v/kauserahamed424-debug/ZapEngine.svg)](https://jitpack.io/#kauserahamed424-debug/ZapEngine)

## Get it (JitPack)

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.kauserahamed424-debug:ZapEngine:1.0.8")
}
```

## Why a race?

| Engine | Strength | Weakness |
|---|---|---|
| ExoPlayer | fastest start, plays most sources | fails cookie/header-protected sources |
| libVLC | plays nearly everything | slower start |
| libmpv | most stable once playing, barely buffers | fails the most sources, slow start |

Sequential failover wastes seconds on every dead end. A race turns start
time into the **fastest** engine's time for that source, and coverage into
the **union** of all three engines.

## How it works

1. **The race** — `play()` starts the stream in all three engines at once.
   First frame wins; the other two stop immediately.
2. **The chain** — if every racer fails on the main URL, the race repeats
   on the backup URL, then on the local-proxy URL, then the proxy backup.
   Only then does `onError` fire — with every engine's real error text.
3. **Engine memory** — the winner (engine + transport + MPV decoder) is
   remembered per channel. The next open starts with that engine alone;
   if it has not framed within 1.5 s, the other two join the race.
4. **Zapping** — a new `play()` cancels the running race instantly
   (generation counter), so rapid channel surfing lands on the last tap.

## Honest costs

- During a race, up to 3 connections pull the same stream for a few
  seconds. Memory mode avoids this for known channels.
- A few sources allow only ONE connection per IP — for those, set
  `preferredEngine` (starts solo; others join after 1.5 s).
- Three engines ≈ 75–90 MB APK (ARM-only). That is the price of carrying
  all three.

## Locked per-engine settings

- **ExoPlayer:** LoadControl 15000/40000/1200/4000 ms; cookies via
  `java.net.CookieManager`; per-request Referer; resize FILL (stretch to the exact surface — no bars, no crop).
- **libVLC 3.6.2:** `:network-caching=1500`, `:http-reconnect`, Chrome UA,
  per-media `:http-referrer`; stretch-to-fill (surface aspect, no bars, no crop).
- **libmpv 0.2.1:** bundled Mozilla `cacert.pem` + `tls-ca-file` +
  `tls-verify=yes`; `demuxer-lavf-o` = ONLY `live_start_index=-1`; probe
  tuning banned; cache readahead 30 s / 40 s / 64 MiB / pause-wait 1;
  `hwdec=mediacodec-copy` + one internal software retry; `keepaspect=no` (stretch-to-fill, no bars, no crop).

## Usage

```kotlin
val player = ZapEngineView(context)
player.listener = object : ZapEngineView.Listener {
    override fun onLoading(req: StreamRequest) { /* show your loading UI */ }
    override fun onPlaying(req: StreamRequest, engine: EngineId) { /* hide it */ }
    override fun onError(req: StreamRequest, errors: List<String>) { /* show errors */ }
}

player.play(
    StreamRequest(
        channelKey = "somoy-tv",
        url = "https://…/somoy/index.m3u8",
        backupUrl = "https://…/backup.m3u8",
        proxyUrl = localProxyUrlFor(mainUrl),       // optional
        proxyBackupUrl = localProxyUrlFor(backupUrl), // optional
        referer = "https://alixbd.com/",              // when the source needs it
        preferredEngine = EngineId.VLC                // hint for known-hard hosts
    )
)

// Transport controls — real calls into whichever engine is on screen:
player.pause()                 // freeze the picture
player.resume()                // continue
player.setVolume(0.5f)         // 0.0 .. 1.0, remembered across channel switches
player.setMuted(true)          // / false restores the previous level
player.setDnsOverHttps(true)   // optional: ExoPlayer loads resolve via
                               // Google Public DNS over HTTPS (default OFF;
                               // VLC/MPV always use the system DNS — their
                               // native stacks expose no override)
```

## Inside the box

- **Three engines racing** every route, first frame wins (see above)
- **Failover chain** — main → backup → local-proxy → proxy-backup, then a
  real error list (every engine's own error text, never a guess)
- **Engine memory** — per-channel winning engine + transport, remembered
- **HLS everywhere**, live streams start at the live edge
- **Cookies, Referer and User-Agent** honoured by all three engines
  (alixbd-type sources need them)
- **TLS that works in MPV** — a Mozilla CA bundle ships in the library;
  MPV cannot read Android's CA store on its own
- **Locked buffer/reconnect tuning** per engine (proven on live TV)
- **Thread-safe race core** — engine callbacks are marshalled to the main
  thread; rapid channel zapping coalesces through a generation counter,
  so stale loads can never jam the player
- **No frozen leftovers** — the instant a channel is tapped, a black
  cover (a plain view, never touching the engines' surfaces) hides the
  previous channel's last frame; it lifts the moment the new first
  frame lands. Rapid drawer taps can no longer freeze the picture or
  the player
- **UI thread never waits** — libVLC/libmpv load/stop/release calls run
  on per-engine background queues (FIFO), never on the main thread.
  VLC's blocking stop() used to freeze the whole app at every channel
  tap — drawer stuck, then the channel suddenly playing; now switching
  stays smooth while the native engines work in the background
- **Transport controls** — pause / resume / volume / mute on the view,
  applied to whichever engine is on screen and kept across switches

## Build

Open the project in Android Studio and run the `:sample` app — it plays
three public test streams and shows which engine won each race and in how
many milliseconds. compileSdk 36 is required by mpv-android-lib 0.2.1.

> API note: the code in this repo was verified on 2026-11-16 against the
> ACTUAL published artifacts (mpv-android-lib-android 0.2.1 sources,
> libvlc-all 3.6.2 classes, Media3 metadata). mpv-android-lib 0.2.x is a
> Kotlin-multiplatform rewrite — there is no static `MPVLib` class (that was
> 0.1.x); everything goes through the `MPV` instance owned by `BaseMPVView`,
> and the player starts paused, so loads explicitly unpause.

## Modules

- `:zapengine` — the library (`ZapEngineView`, 3 engine wrappers, memory)
- `:sample` — the demo app
- `docs/INTEGRATION-BDTV.md` — how the BDTv app integrates ZapEngine

## License

ZapEngine's own code is MIT-licensed (see `LICENSE`) — © Kauser Ahamed.
The bundled engines keep their own licenses: Media3/ExoPlayer (Apache-2.0),
libVLC (LGPL), libmpv (LGPL/GPL). Respect those terms when redistributing.
