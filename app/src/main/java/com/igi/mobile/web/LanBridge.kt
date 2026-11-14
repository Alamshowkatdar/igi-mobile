package com.igi.mobile.web

import android.util.Log
import android.webkit.JavascriptInterface
import com.igi.mobile.GameActivity
import org.json.JSONArray
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Backs `window.IgiLan`, the small bridge used by `assets/lan-webrtc.js`.
 *
 * The game itself has no idea it is running inside a WebView, so the app has to supply the
 * phone's own LAN address (used to replace mDNS WebRTC candidates) and collect a report when
 * a local pairing stalls instead of connecting.
 */
class LanBridge(private val activity: GameActivity) {

    @JavascriptInterface
    fun log(message: String) {
        Log.i(TAG, message)
    }

    /** JSON array of this phone's site-local IPv4 addresses, best Wi-Fi candidate first. */
    @JavascriptInterface
    fun localIpv4(): String = JSONArray(localAddresses()).toString()

    @JavascriptInterface
    fun stalled(report: String) {
        Log.w(TAG, "Local co-op stalled: $report")
        activity.runOnUiThread { activity.showLanStallReport(report) }
    }

    private fun localAddresses(): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    if (!address.isSiteLocalAddress) continue
                    val host = address.hostAddress ?: continue
                    found += rank(nif.name.orEmpty()) to host
                }
            }
        }
        return found.sortedWith(compareBy({ it.first }, { it.second })).map { it.second }.distinct()
    }

    /** Prefer the Wi-Fi interface over hotspots, USB tethering and anything else. */
    private fun rank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("ap") || name.startsWith("swlan") -> 1
        name.startsWith("eth") -> 2
        name.startsWith("rndis") || name.startsWith("usb") -> 3
        else -> 4
    }

    companion object {
        const val NAME = "IgiLan"
        const val TAG = "IgiGame"
    }
}
