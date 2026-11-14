package com.igi.mobile.web

import android.util.Log
import android.webkit.WebView

/**
 * Injects `assets/lan-webrtc.js` into the game page.
 *
 * The script wraps `RTCPeerConnection`, and the game looks that constructor up at the moment
 * it opens a co-op link, so it does not have to run before the bundle starts: installing it
 * from `onPageFinished` is enough, and re-installing after a reload is harmless.
 */
object LanWebRtc {

    private const val ASSET = "lan-webrtc.js"

    private var cached: String? = null

    fun install(view: WebView) {
        val script = script(view) ?: return
        view.evaluateJavascript(script, null)
    }

    private fun script(view: WebView): String? {
        cached?.let { return it }
        val text = runCatching {
            view.context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.onFailure {
            Log.w(LanBridge.TAG, "Could not read $ASSET", it)
        }.getOrNull()
        cached = text
        return text
    }
}
