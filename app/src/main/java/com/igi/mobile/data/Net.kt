package com.igi.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object Net {
    // A normal Chrome for Android fingerprint. WebView sends "wv" by default, which some CDNs treat
    // differently; the game and its data host both serve this UA happily.
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun requestBuilder(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        // data.playigi.com rejects requests that do not carry the game page as referrer.
        .header("Referer", "https://playigi.com/game/")
        .header("Accept", "*/*")

    fun request(url: String): Request = requestBuilder(url).build()

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Throws(IOException::class)
    fun getText(url: String): String {
        client.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    @Throws(IOException::class)
    fun download(url: String, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        var offset = if (temp.isFile) temp.length() else 0L

        val builder = requestBuilder(url)
        if (offset > 0L) builder.header("Range", "bytes=$offset-")

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 200) offset = 0L
            else if (response.code != 206) throw IOException("HTTP ${response.code} for $url")

            val body = response.body ?: throw IOException("Empty body for $url")
            FileOutputStream(temp, offset > 0L).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }

        if (temp.length() <= 0L) throw IOException("Downloaded an empty file: $url")
        if (target.exists() && !target.delete()) throw IOException("Cannot replace $target")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}
