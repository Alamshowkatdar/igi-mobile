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

    private const val GYRO_MARKER = "__IGI_GYRO_FIX__"

    /**
     * The game checks for the Generic Sensor API first and only falls back to its broken
     * `deviceorientation` pitch math when `Gyroscope`/`GravitySensor` are missing. Android WebView
     * does not provide them, so this shim supplies both from the phone's native sensors via
     * [com.igi.mobile.web.GyroBridge]. The game's own gravity-compensated handler then drives both
     * yaw and pitch, with its sensitivity and invert setting intact.
     */
    private const val GYRO_SCRIPT = """<script id="__IGI_GYRO_FIX__">(function(){
var W=window;
function sensor(){
var listeners=[],s={x:0,y:0,z:0,
addEventListener:function(t,f){if(t==='reading'||t==='error')listeners.push({t:t,f:f});},
removeEventListener:function(t,f){listeners=listeners.filter(function(l){return l.t!==t||l.f!==f;});},
start:function(){},stop:function(){},
emit:function(x,y,z){s.x=x;s.y=y;s.z=z;for(var i=0;i<listeners.length;i++)if(listeners[i].t==='reading')listeners[i].f();}};
return s;}
var gyro=sensor(),gravity=sensor();
W.Gyroscope=function(){return gyro;};
W.GravitySensor=function(){return gravity;};
setInterval(function(){
var v;try{v=W.IgiGyro.sample();}catch(e){return;}
if(!v)return;var a=v.split(',');if(a.length<6)return;
gyro.emit(+a[0],+a[1],+a[2]);
gravity.emit(+a[3],+a[4],+a[5]);
},16);
})();</script>"""

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
     * Inserts the boot script that disables the site's service worker and the gyro shim. Idempotent
     * (a shell patched by an older build gets the missing script), and also used by
     * [BundleInstaller] because the bundled copy of index.html comes straight from the server.
     */
    fun patchIndex(file: File) {
        var html = file.readText()
        var changed = false
        if (!html.contains(BOOT_MARKER)) {
            html = insertScript(html, BOOT_SCRIPT)
            changed = true
        }
        // Replace, not just insert: shells patched by the earlier WebView-only shim still carry
        // the same marker but disable Gyroscope/GravitySensor, which would defeat this fix.
        val start = html.indexOf("<script id=\"$GYRO_MARKER\">", ignoreCase = true)
        if (start < 0) {
            html = insertScript(html, GYRO_SCRIPT)
            changed = true
        } else {
            val end = html.indexOf("</script>", start)
            if (end >= 0) {
                val close = end + "</script>".length
                if (html.substring(start, close) != GYRO_SCRIPT) {
                    html = html.substring(0, start) + GYRO_SCRIPT + html.substring(close)
                    changed = true
                }
            }
        }
        if (changed) file.writeText(html)
    }

    private fun insertScript(html: String, script: String): String {
        val head = html.indexOf("<head>", ignoreCase = true)
        return if (head >= 0) {
            val at = head + "<head>".length
            html.substring(0, at) + script + html.substring(at)
        } else {
            script + html
        }
    }
}
