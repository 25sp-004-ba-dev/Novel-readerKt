package com.example

import android.webkit.JavascriptInterface

class WtrWebAppInterface(
    private val onPlaybackStateChanged: (isPlaying: Boolean, title: String, subtitle: String) -> Unit,
    private val onUrlSynced: (url: String, title: String) -> Unit = { _, _ -> }
) {
    @JavascriptInterface
    fun syncUrl(url: String, title: String) {
        onUrlSynced(url, title)
    }

    @JavascriptInterface
    fun postPlaybackState(isPlaying: Boolean, title: String, subtitle: String) {
        onPlaybackStateChanged(isPlaying, title, subtitle)
    }

    @JavascriptInterface
    fun syncPollState(isPlaying: Boolean, title: String, subtitle: String = "") {
        val sub = if (subtitle.isNotEmpty()) {
            subtitle
        } else {
            if (isPlaying) "Playing Wtr-Lab Novel" else "Paused"
        }
        onPlaybackStateChanged(isPlaying, title, sub)
    }

    @JavascriptInterface
    fun speakNative(text: String, rate: Float, pitch: Float, lang: String) {
        WtrAudioControlBridge.onSpeakNative?.invoke(text, rate, pitch, lang)
    }

    @JavascriptInterface
    fun cancelNative() {
        WtrAudioControlBridge.onCancelNative?.invoke()
    }

    @JavascriptInterface
    fun pauseNative() {
        WtrAudioControlBridge.onPauseNative?.invoke()
    }

    @JavascriptInterface
    fun resumeNative() {
        WtrAudioControlBridge.onResumeNative?.invoke()
    }
}
