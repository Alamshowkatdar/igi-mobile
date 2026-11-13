package com.igi.mobile.web

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

class GameWebViewClient(
    private val shellDir: File,
    private val dataDir: File,
    private val onExternalLink: (Uri) -> Unit
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (!url.scheme.equals("https", ignoreCase = true)) return null
        val host = url.host ?: return null
        val path = url.path ?: return null

        val file: File = when {
            host.equals("playigi.com", ignoreCase = true) &&
                (path == "/game" || path.startsWith("/game/")) -> {
                var relative = path.removePrefix("/game").removePrefix("/")
                if (relative.isEmpty()) relative = "index.html"
                File(shellDir, relative)
            }

            host.equals("data.playigi.com", ignoreCase = true) -> {
                File(dataDir, path.removePrefix("/"))
            }

            else -> return null
        }

        if (!file.isFile) return null
        return LocalAssets.response(file, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val host = url.host ?: return true
        val isGame = host.equals("playigi.com", ignoreCase = true) &&
            (url.path?.startsWith("/game") == true)
        if (isGame) return false

        // Everything else (Discord, the main site, credits) belongs in the real browser.
        onExternalLink(url)
        return true
    }
}
