package com.igi.mobile.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Downloads and patches the game shell (page, JS bundle, CSS, fonts, icons, radio audio).
 * The service worker is disabled by the injected script: the app's own cache layer serves every
 * request, which is what makes fully offline launches possible.
 */
object ShellCache {

    private const val BOOT_MARKER = "__IGI_APP_BOOT__"

    private const val BOOT_SCRIPT = """<script id="__IGI_APP_BOOT__">(function(){try{
var sw=navigator.serviceWorker;
if(sw){sw.register=function(){return Promise.reject(new Error('service worker disabled by IGI Mobile'));};
if(sw.getRegistrations){sw.getRegistrations().then(function(rs){rs.forEach(function(r){try{r.unregister();}catch(e){}});});}}}
catch(e){}})();</script>"""

    suspend fun ensure(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Boolean {
        val shellDir = GamePaths.shellDir(context)
        val listFile = GamePaths.shellListCache(context)
        val paths = try {
            val text = Net.getText(GamePaths.SHELL_LIST_URL)
            listFile.parentFile?.mkdirs()
            listFile.writeText(text)
            parseList(text)
        } catch (error: Exception) {
            if (listFile.isFile) {
                runCatching { parseList(listFile.readText()) }.getOrElse { return false }
            } else {
                return false
            }
        }

        val all = (paths + "index.html" + "manifest.webmanifest").distinct()
        var done = 0
        for (relative in all) {
            val target = File(shellDir, relative)
            if (!(target.isFile && target.length() > 0L)) {
                runCatching { Net.download(GamePaths.SITE + relative, target) }
            }
            done++
            onProgress(done, all.size)
        }

        val index = File(shellDir, "index.html")
        if (index.isFile && index.length() > 0L) {
            runCatching { patchIndex(index) }
            return true
        }
        return false
    }

    fun parseList(text: String): List<String> {
        val array = JSONArray(text)
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val path = array.optString(i).trim()
            if (path.isNotEmpty() && !path.startsWith("http")) out.add(path.removePrefix("/game/"))
        }
        return out
    }

    /**
     * Inserts the boot script that disables the site's service worker. Idempotent, and also used
     * by [BundleInstaller] because the bundled copy of index.html comes straight from the server.
     */
    fun patchIndex(file: File) {
        val html = file.readText()
        if (html.contains(BOOT_MARKER)) return
        val head = html.indexOf("<head>", ignoreCase = true)
        val patched = if (head >= 0) {
            val at = head + "<head>".length
            html.substring(0, at) + BOOT_SCRIPT + html.substring(at)
        } else {
            BOOT_SCRIPT + html
        }
        file.writeText(patched)
    }
}
