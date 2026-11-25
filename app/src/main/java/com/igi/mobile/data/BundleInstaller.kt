package com.igi.mobile.data

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Expands the optional asset bundle produced by `tools/FetchBundle.java`.
 *
 * The bundle is a pair of zips in `assets/bundle/`: the web shell and the game data. They are
 * copied into the same directories the downloader uses, so the WebView interception layer does
 * not need to know whether a file came from the APK or the network.
 *
 * When the assets are absent (the default, asset-free build) every helper here is a no-op and
 * the app falls back to downloading from playigi.com.
 */
object BundleInstaller {

    private const val TAG = "BundleInstaller"
    private const val ASSET_DIR = "bundle"
    private const val ASSET_VERSION = "$ASSET_DIR/version.txt"
    private const val ASSET_SHELL = "$ASSET_DIR/shell.zip"
    private const val ASSET_DATA = "$ASSET_DIR/data.zip"
    private const val MARKER = "bundle.version"
    private const val BUFFER = 256 * 1024

    data class Info(
        val version: String,
        val dataBytes: Long,
        val shellEntries: Int,
        val dataEntries: Int
    ) {
        val totalEntries: Int get() = shellEntries + dataEntries
    }

    fun hasBundle(context: Context): Boolean = info(context) != null

    fun info(context: Context): Info? = try {
        context.assets.open(ASSET_VERSION).bufferedReader().use { reader ->
            val values = reader.readLines()
                .mapNotNull { line ->
                    val at = line.indexOf('=')
                    if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
                }
                .toMap()
            val version = values["version"]
            if (version.isNullOrBlank()) null else Info(
                version = version,
                dataBytes = values["dataBytes"]?.toLongOrNull() ?: 0L,
                shellEntries = values["shellEntries"]?.toIntOrNull() ?: 0,
                dataEntries = values["dataEntries"]?.toIntOrNull() ?: 0
            )
        }
    } catch (error: Exception) {
        null
    }

    fun installedVersion(context: Context): String? = try {
        val marker = File(GamePaths.metaDir(context), MARKER)
        if (marker.isFile) marker.readText().trim() else null
    } catch (error: Exception) {
        null
    }

    fun isInstalled(context: Context): Boolean {
        val info = info(context) ?: return false
        if (installedVersion(context) != info.version) return false
        val index = File(GamePaths.shellDir(context), "index.html")
        return index.isFile && index.length() > 0L
    }

    /**
     * Extracts the bundle if needed. Safe to call repeatedly; only one call does the work at a
     * time. Returns true when the shell is available afterwards.
     */
    @Synchronized
    fun prepare(context: Context, onProgress: (Float) -> Unit = {}): Boolean {
        val info = info(context) ?: return false
        if (isInstalled(context)) {
            // Older installs may predate the boot/gyro script patches; make sure both are present.
            runCatching { ShellCache.patchIndex(File(GamePaths.shellDir(context), "index.html")) }
            onProgress(1f)
            return true
        }

        val shellDir = GamePaths.shellDir(context)
        val dataDir = GamePaths.dataDir(context)
        val total = info.totalEntries.coerceAtLeast(1)
        var done = 0

        return try {
            shellDir.mkdirs()
            dataDir.mkdirs()

            extract(context.assets, ASSET_SHELL, shellDir) { entries ->
                done += entries
                onProgress(done.toFloat() / total)
            }
            extract(context.assets, ASSET_DATA, dataDir) { entries ->
                done += entries
                onProgress(done.toFloat() / total)
            }

            // The zip came straight from the site, so block its service worker the same way the
            // network shell download does. Without this the SW can take over the page.
            runCatching { ShellCache.patchIndex(File(shellDir, "index.html")) }

            copyIndex(context)
            File(GamePaths.metaDir(context), MARKER).apply {
                parentFile?.mkdirs()
                writeText(info.version)
            }
            onProgress(1f)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not expand the bundled game data", error)
            // Drop the marker so the next attempt does not trust a partial extraction.
            File(GamePaths.metaDir(context), MARKER).delete()
            false
        }
    }

    private fun copyIndex(context: Context) {
        val target = GamePaths.indexCache(context)
        target.parentFile?.mkdirs()
        context.assets.open("offline-index.json").use { input ->
            target.outputStream().use { output -> input.copyTo(output, BUFFER) }
        }
    }

    private inline fun extract(
        assets: AssetManager,
        assetPath: String,
        root: File,
        onEntries: (Int) -> Unit
    ) {
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(assets.open(assetPath), BUFFER)).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            val buffer = ByteArray(BUFFER)
            while (entry != null) {
                val name = entry.name
                if (name.isNotBlank() && !name.contains("..")) {
                    val target = File(root, name)
                    val safe = target.canonicalPath.startsWith(rootPath)
                    if (safe) {
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                }
                zip.closeEntry()
                count++
                if (count % 120 == 0) onEntries(120)
                entry = zip.nextEntry
            }
            if (count % 120 != 0) onEntries(count % 120)
        }
    }
}
