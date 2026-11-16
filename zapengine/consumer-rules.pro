# Keep the three engine entry points (consumer apps using R8 keep their own rules too).
-keep class org.videolan.libvlc.** { *; }
-keep class is.xyz.mpv.** { *; }
