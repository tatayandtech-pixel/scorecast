# ScoreCast R8 rules.
#
# Scope note: the `scorecast` repo is public, so these rules are NOT here to hide the code —
# the original source is a clone away. They exist to (a) let R8 shrink/optimize a release build
# without breaking it, and (b) keep future debug logging out of shipped builds.

# ── StreamPack ────────────────────────────────────────────────────────────────────────────────
# StreamPack 3.1.2 ships NO consumer ProGuard rules of its own (verified: none of the three AARs
# contain a proguard.txt). Its DynamicEndpoint resolves the RTMP endpoint reflectively, by the
# fully-qualified name
#     io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.RtmpEndpoint
# so R8 would rename or strip it and the only symptom would be a dead stream at runtime, on
# release builds only. Keeping the whole library (plus komuxer, the RTMP implementation beneath
# it) is deliberate: a little APK size is a cheap price for not regressing a pipeline that is
# verified working on-device.
-keep class io.github.thibaultbee.streampack.** { *; }
-keep class io.github.komedia.komuxer.** { *; }
-dontwarn io.github.thibaultbee.streampack.**
-dontwarn io.github.komedia.komuxer.**

# streampack-core also names the SRT sink reflectively, but we don't depend on streampack-srt —
# R8 would otherwise warn about the missing class on every release build.
-dontwarn io.github.thibaultbee.streampack.ext.srt.**

# ── Logging ───────────────────────────────────────────────────────────────────────────────────
# Strip verbose/debug logging from release builds. Deliberately NOT Log.i/w/e: all six Log calls
# in the app today are i/w/e, and four of them (FirebaseSessionSync's sync-died and pushDiff-failed
# lines, StreamerHolder's stream error, OverlayCompositor's drawFrame failure) are the diagnostics
# an on-device repro depends on. Removing them would make release builds undebuggable for exactly
# the open issues we still need to chase.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ── Notes on what needs no rules here ─────────────────────────────────────────────────────────
# Firebase Realtime Database: our mapping is hand-written (FirebaseStateMapping.kt uses explicit
# child()/getValue(String::class.java) calls, never POJO serialization), so no model classes need
# keeping. Sport configs are parsed the same way, by explicit key. Compose, coroutines, Firebase,
# ZXing and the Facebook SDK all ship their own consumer rules.
