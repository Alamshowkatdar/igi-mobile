package com.igi.mobile.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Serves cached files to the WebView with the MIME types, CORS headers and byte-range support the
 * game needs. `.gz` payloads are served raw (no Content-Encoding) because the game decompresses
 * them itself with DecompressionStream('gzip').
 */
object LocalAssets {

    fun response(file: File, request: WebResourceRequest): WebResourceResponse? {
        if (!file.isFile) return null
        val length = file.length()
        val mime = mimeOf(file.name)
        val headers = HashMap<String, String>()
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Access-Control-Allow-Headers"] = "*"
        headers["Access-Control-Allow-Methods"] = "GET,HEAD,OPTIONS"
        headers["Cache-Control"] = "no-cache"
        headers["Accept-Ranges"] = "bytes"

        val range = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value

        if (range != null && length > 0L) {
            val match = Regex("bytes=(\\d*)-(\\d*)").find(range)
            if (match != null) {
                val start = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: 0L
                var end = match.groupValues[2].ifEmpty { (length - 1).toString() }.toLongOrNull() ?: (length - 1)
                if (start in 0 until length && end >= start) {
                    end = minOf(end, length - 1)
                    val count = end - start + 1
                    headers["Content-Range"] = "bytes $start-$end/$length"
                    headers["Content-Length"] = count.toString()
                    val input = FileInputStream(file)
                    skipFully(input, start)
                    return WebResourceResponse(
                        mime,
                        null,
                        206,
                        "Partial Content",
                        headers,
                        LimitedInputStream(input, count)
                    )
                }
            }
        }

        headers["Content-Length"] = length.toString()
        val encoding = if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "UTF-8" else null
        return WebResourceResponse(mime, encoding, 200, "OK", headers, FileInputStream(file))
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        var buffer: ByteArray? = null
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (buffer == null) buffer = ByteArray(8 * 1024)
                val read = input.read(buffer)
                if (read < 0) break
                remaining -= read.toLong()
            }
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "webmanifest" -> "application/manifest+json"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "mp3" -> "audio/mpeg"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "webm" -> "video/webm"
        "mp4", "m4v" -> "video/mp4"
        "wasm" -> "application/wasm"
        "bin", "gz", "dat", "pk3" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) :
        FilterInputStream(input) {

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(length.toLong(), remaining).toInt()
            val read = super.read(buffer, offset, toRead)
            if (read > 0) remaining -= read.toLong()
            return read
        }

        override fun skip(count: Long): Long {
            val toSkip = minOf(count, remaining)
            val skipped = super.skip(toSkip)
            remaining -= skipped
            return skipped
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt().coerceAtLeast(0)
    }
}
