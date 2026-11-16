# Integrating ZapEngine into the BDTv app

Goal: replace the internals of the current player with the triple-engine
race, **without changing anything the viewer sees** — same screens, same
drawer, same original TV loading animation, same remote behaviour.

## Steps

1. **Add the module.** Copy the `zapengine/` folder into the BDTv project,
   `include(":zapengine")` in `settings.gradle.kts`, and add
   `implementation(project(":zapengine"))` to the app module.
   Remove the old Media3/libVLC/mpv dependencies from the app module — the
   library now carries all three (Media3 1.8.0, libVLC 3.6.2,
   mpv-android-lib 0.2.1). Keep compileSdk 36 / targetSdk 35.

2. **Swap the player screen's engine layer.** Where `NativePlayerManager`
   used to own ExoPlayer/VLC/MPV, put ONE `ZapEngineView` filling the
   player area. Keep the existing view stack order:
   - Layer 1: `ZapEngineView` (the three surfaces live inside it)
   - Layer 2: `startup_loader` — the ORIGINAL TV animation, unchanged
   - Layer 3: drawer / grid / dialogs (web_view layer), unchanged

3. **Wire the listener to the existing UI behaviour:**
   - `onLoading` → show the TV animation (exactly as today)
   - `onPlaying` → hide the animation (first frame is already on screen)
   - `onError` → show the app's existing error state

4. **Build one StreamRequest per channel tap** from the channel DB row:
   - `channelKey` = the channel's stable id
   - `url` = main URL, `backupUrl` = backup_url
   - `proxyUrl` / `proxyBackupUrl` = the same URLs rewritten through the
     app's existing `LocalStreamProxy` (unchanged — the proxy already
     streams byte-by-byte per the v31 fix)
   - `referer` = the channel's stored referer
   - `preferredEngine` = `EngineId.VLC` when the URL is an alixbd URL,
     otherwise null

5. **Delete the old failover code.** The chain (main → backup → proxy →
   proxy backup), watchdogs, switch-generation handling and per-channel
   engine memory now live inside the library. The app keeps: channel list
   (cache-first), drawer, fullscreen handling, smart last-channel restore
   (call `play()` with the last channel on open), and the remote keys —
   Channel Up/Down and grid OK simply call `play()` for the chosen channel.

6. **Keep the logs.** The library logs under `ZapEngine`; per-engine
   error strings surface through `onError`. For deeper diagnosis keep the
   existing `BDTvPlayback` logging around the `play()` call sites.

## What does NOT change

- No visual design changes anywhere. The loading animation is the owner's
  original TV animation, restored from git history — not replaced.
- Fullscreen-on-open with edge-to-edge fill in all three engines.
- Admin panel, channels.json sync, local proxy, Telegram flows — untouched.
- Version stays 3.1.7 (317) unless the owner decides otherwise.

## Verify on a real device (measured, not code inspection)

1. Somoy, Ekhon, one SBOX (http) channel, one alixbd channel, the two
   slowest channels: tap→first-frame milliseconds + which engine won
   (the sample app's status line pattern can be copied into a debug build,
   or read the `ZapEngine` logs).
2. Three channels that FAIL in MPV alone — they must play (VLC/Exo win).
3. Rapid-tap 10 channels in ~5 s — the last one plays, no jam, no frozen
   held frame.
4. Reopen a VLC-won channel — it must start on VLC directly (memory).
5. Ten minutes on one channel per engine — count mid-play stalls.
