package com.igi.mobile.data

import android.content.Context
import java.io.File

/**
 * Every byte the app caches lives in app-specific storage. Nothing is written to shared storage,
 * and nothing is bundled in the APK: the game shell and game data are downloaded from the public
 * playigi.com web port on the user's device.
 */
object GamePaths {
    const val SITE = "https://playigi.com/game/"
    const val SITE_ORIGIN = "https://playigi.com"
    const val DATA_ORIGIN = "https://data.playigi.com"
    const val INDEX_URL = "$DATA_ORIGIN/offline-index.json"
    const val SHELL_LIST_URL = "${SITE}shell-files.json"

    fun root(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "igi")
    }

    fun shellDir(context: Context): File = File(root(context), "shell")

    fun dataDir(context: Context): File = File(root(context), "data")

    fun metaDir(context: Context): File = File(root(context), "meta")

    fun indexCache(context: Context): File = File(metaDir(context), "offline-index.json")

    fun shellListCache(context: Context): File = File(metaDir(context), "shell-files.json")

    fun missionGameUrl(touch: Boolean): String = "$SITE${if (touch) "?touch=1" else ""}"
}
