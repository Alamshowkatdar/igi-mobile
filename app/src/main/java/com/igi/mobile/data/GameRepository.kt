package com.igi.mobile.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val speedBytesPerSecond: Long = 0L
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

data class IgiState(
    val ready: Boolean = false,
    val index: OfflineIndex? = null,
    val indexError: String? = null,
    val shellReady: Boolean = false,
    val preparingShell: Boolean = false,
    val shellProgress: Float = 0f,
    val bundledBuild: Boolean = false,
    val bundlePreparing: Boolean = false,
    val bundleProgress: Float = 0f,
    val onDeviceBytes: Long = 0L,
    val installedMissions: Set<Int> = emptySet(),
    val downloading: Boolean = false,
    val cancelling: Boolean = false,
    val progress: DownloadProgress? = null,
    val message: String? = null,
    val error: String? = null,
    val hdPack: Boolean = false,
    val flacPack: Boolean = false,
    val touchControls: Boolean = true,
    val wifiOnly: Boolean = true,
    val accepted: Boolean = false
)

class GameRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("igi_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(IgiState())
    val state: StateFlow<IgiState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    companion object {
        private const val TAG = "IgiRepository"
        private const val KEY_HD = "hd_pack"
        private const val KEY_FLAC = "flac_pack"
        private const val KEY_TOUCH = "touch_controls"
        private const val KEY_WIFI = "wifi_only"
        private const val KEY_ACCEPTED = "accepted"

        val MISSION_NAMES = listOf(
            "Trainyard",
            "SAM Base",
            "Military Airbase",
            "GOD",
            "Radar Base",
            "Get Priboi",
            "Border Crossing",
            "Re-supply",
            "Missile Trainyard",
            "Defend Priboi",
            "Eagle's Nest I",
            "Eagle's Nest II",
            "Nuclear Infiltration",
            "Finding the bomb"
        )

        @Volatile
        private var instance: GameRepository? = null

        fun get(context: Context): GameRepository =
            instance ?: synchronized(this) {
                instance ?: GameRepository(context).also { instance = it }
            }
    }

    init {
        _state.update {
            it.copy(
                hdPack = prefs.getBoolean(KEY_HD, false),
                flacPack = prefs.getBoolean(KEY_FLAC, false),
                touchControls = prefs.getBoolean(KEY_TOUCH, true),
                wifiOnly = prefs.getBoolean(KEY_WIFI, true),
                accepted = prefs.getBoolean(KEY_ACCEPTED, false)
            )
        }
    }

    fun warmUp() {
        scope.launch {
            val bundled = BundleInstaller.hasBundle(app)
            val needsExtract = bundled && !BundleInstaller.isInstalled(app)
            _state.update {
                it.copy(
                    bundledBuild = bundled,
                    bundlePreparing = needsExtract
                )
            }
            if (needsExtract) {
                Log.i(TAG, "Expanding bundled game data")
                val ok = BundleInstaller.prepare(app) { progress ->
                    _state.update { it.copy(bundleProgress = progress.coerceIn(0f, 1f)) }
                }
                _state.update {
                    it.copy(bundlePreparing = false, bundleProgress = if (ok) 1f else 0f)
                }
            } else if (bundled) {
                // Already expanded, but prepare() also applies the service-worker boot patch
                // (and re-validates the install), so run it on every launch.
                BundleInstaller.prepare(app)
                _state.update { it.copy(bundleProgress = 1f) }
            }

            val cached = loadCachedIndex()
            val bundledIndex = if (cached == null) loadBundledIndex() else null
            val initial = cached ?: bundledIndex
            if (initial != null) {
                _state.update { it.copy(index = initial, ready = true) }
                scanInstalled(initial)
            }
            _state.update { it.copy(shellReady = isShellReady()) }
            if (Net.isOnline(app)) {
                refreshIndexNow()
            }
        }
    }

    private fun loadCachedIndex(): OfflineIndex? =
        try {
            val file = GamePaths.indexCache(app)
            if (file.isFile) OfflineIndex.parse(file.readText()) else null
        } catch (error: Exception) {
            Log.w(TAG, "Cached index is unreadable", error)
            null
        }

    private fun loadBundledIndex(): OfflineIndex? =
        try {
            app.assets.open("offline-index.json").bufferedReader().use { reader ->
                OfflineIndex.parse(reader.readText())
            }
        } catch (error: Exception) {
            Log.w(TAG, "Bundled index is unreadable", error)
            null
        }

    fun refreshIndex() {
        scope.launch { refreshIndexNow() }
    }

    private suspend fun refreshIndexNow() {
        try {
            val text = Net.getText(GamePaths.INDEX_URL)
            val index = OfflineIndex.parse(text)
            val cache = GamePaths.indexCache(app)
            cache.parentFile?.mkdirs()
            cache.writeText(text)
            _state.update { it.copy(index = index, ready = true, indexError = null) }
            scanInstalled(index)
            _state.update { it.copy(shellReady = isShellReady()) }
        } catch (error: Exception) {
            Log.w(TAG, "Could not refresh the offline index", error)
            _state.update { it.copy(indexError = "Could not refresh the game index. Using the bundled copy.") }
        }
    }

    private suspend fun scanInstalled(index: OfflineIndex) = withContext(Dispatchers.IO) {
        val dataDir = GamePaths.dataDir(app)
        val present = HashMap<String, Long>()
        if (dataDir.isDirectory) {
            dataDir.walkTopDown().forEach { file ->
                if (file.isFile && !file.name.endsWith(".part")) {
                    present[file.relativeTo(dataDir).invariantSeparatorsPath] = file.length()
                }
            }
        }
        val categories = categories(index)
        val installed = index.levels.keys.filter { mission ->
            index.filesFor(mission, categories).values.all { pack ->
                val length = present[pack.path] ?: return@all false
                if (pack.size > 0L) length == pack.size else length > 0L
            }
        }.toSet()

        val onDevice = GamePaths.root(app).walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        _state.update {
            it.copy(
                installedMissions = installed,
                onDeviceBytes = onDevice,
                shellReady = isShellReady()
            )
        }
    }

    fun isShellReady(): Boolean {
        val index = File(GamePaths.shellDir(app), "index.html")
        return index.isFile && index.length() > 0L
    }

    suspend fun ensureShell(): Boolean = withContext(Dispatchers.IO) {
        if (!isShellReady() && BundleInstaller.hasBundle(app) && !BundleInstaller.isInstalled(app)) {
            _state.update { it.copy(bundlePreparing = true, bundleProgress = 0f) }
            val expanded = BundleInstaller.prepare(app) { progress ->
                _state.update { it.copy(bundleProgress = progress.coerceIn(0f, 1f)) }
            }
            _state.update { it.copy(bundlePreparing = false, bundleProgress = if (expanded) 1f else 0f) }
            if (expanded) {
                loadCachedIndex()?.let { index ->
                    _state.update { it.copy(index = index, ready = true) }
                    scanInstalled(index)
                }
            }
        }
        if (isShellReady()) {
            // Patch even an already-cached shell: older builds injected a deviceorientation-only
            // shim that must be replaced by the native sensor bridge.
            runCatching { ShellCache.patchIndex(File(GamePaths.shellDir(app), "index.html")) }
            _state.update { it.copy(shellReady = true) }
            return@withContext true
        }
        if (!Net.isOnline(app)) {
            _state.update { it.copy(error = "Connect to the internet once so the game shell can be downloaded.") }
            return@withContext false
        }
        _state.update { it.copy(preparingShell = true, shellProgress = 0f, error = null) }
        val ok = try {
            ShellCache.ensure(app) { done, total ->
                _state.update { it.copy(shellProgress = done.toFloat() / total.coerceAtLeast(1).toFloat()) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Shell download failed", error)
            false
        }
        _state.update {
            it.copy(
                preparingShell = false,
                shellReady = isShellReady(),
                error = if (ok) null else "Could not prepare the game shell. Check your connection and try again."
            )
        }
        ok
    }

    private fun categories(index: OfflineIndex? = _state.value.index): Set<String> {
        val snapshot = _state.value
        return index?.categories(
            if (snapshot.hdPack) OfflineIndex.HD else OfflineIndex.SD,
            snapshot.touchControls,
            snapshot.flacPack
        ) ?: emptySet()
    }

    fun missions(): List<Int> = _state.value.index?.levels?.keys?.sorted() ?: (1..14).toList()

    fun missionName(mission: Int): String = MISSION_NAMES.getOrElse(mission - 1) { "Mission $mission" }

    fun allInstallBytes(): Long {
        val index = _state.value.index ?: return 0L
        return index.allFiles(categories(index)).values.sumOf { it.size }
    }

    fun bytesFor(missions: Set<Int>): Long {
        val index = _state.value.index ?: return 0L
        return index.bytesFor(missions, categories(index))
    }

    fun missionBytes(mission: Int): Long {
        val index = _state.value.index ?: return 0L
        return index.filesFor(mission, categories(index)).values.sumOf { it.size }
    }

    fun setHdPack(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HD, enabled).apply()
        _state.update { it.copy(hdPack = enabled) }
        rescan()
    }

    fun setFlacPack(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLAC, enabled).apply()
        _state.update { it.copy(flacPack = enabled) }
        rescan()
    }

    fun setTouchControls(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOUCH, enabled).apply()
        _state.update { it.copy(touchControls = enabled) }
        rescan()
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI, enabled).apply()
        _state.update { it.copy(wifiOnly = enabled) }
    }

    fun acceptDisclaimer() {
        prefs.edit().putBoolean(KEY_ACCEPTED, true).apply()
        _state.update { it.copy(accepted = true) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun rescan() {
        val index = _state.value.index ?: return
        scope.launch { scanInstalled(index) }
    }

    fun startDownload(missions: Set<Int>, allMissions: Boolean) {
        if (_state.value.downloading) return
        if (!Net.isOnline(app)) {
            _state.update { it.copy(error = "You are offline. Connect to the internet to download game data.") }
            return
        }
        if (_state.value.wifiOnly && !Net.isUnmetered(app)) {
            _state.update {
                it.copy(error = "Wi-Fi only is enabled. Connect to Wi-Fi or turn off Wi-Fi only in Settings.")
            }
            return
        }
        if (_state.value.index == null) {
            _state.update { it.copy(error = "The game index is not ready yet.") }
            return
        }
        if (!allMissions && missions.isEmpty()) return
        DownloadService.start(app, missions, allMissions)
    }

    fun cancelDownload() {
        cancelRequested = true
        _state.update { it.copy(cancelling = true) }
    }

    /** Called by DownloadService. Runs until every file is present or the user cancels. */
    suspend fun performDownload(
        missions: Set<Int>,
        allMissions: Boolean,
        onTick: (IgiState) -> Unit = {}
    ) {
        val index = _state.value.index ?: return
        val categories = categories(index)
        val wanted = if (allMissions) index.allFiles(categories) else {
            val union = LinkedHashMap<String, PackFile>()
            for (mission in missions) index.filesFor(mission, categories).forEach { (k, v) -> union.putIfAbsent(k, v) }
            union
        }

        val dataDir = GamePaths.dataDir(app)
        val missing = wanted.values.filter { !isInstalled(it, dataDir) }
        if (missing.isEmpty()) {
            _state.update {
                it.copy(
                    downloading = false,
                    progress = null,
                    message = "Game data is already installed."
                )
            }
            scanInstalled(index)
            onTick(_state.value)
            return
        }

        cancelRequested = false
        val totalBytes = missing.sumOf { it.size }
        val doneBytes = AtomicLong(0L)
        val doneFiles = AtomicInteger(0)
        val totalFiles = missing.size
        val startedAt = System.currentTimeMillis()

        // Count already-downloaded .part bytes so resumed downloads report honest progress.
        for (file in missing) {
            val part = File(File(dataDir, file.path).parentFile, File(file.path).name + ".part")
            if (part.isFile) doneBytes.addAndGet(part.length())
        }

        val totalWithParts = totalBytes
        _state.update {
            it.copy(
                downloading = true,
                cancelling = false,
                error = null,
                message = null,
                progress = DownloadProgress(
                    doneBytes = doneBytes.get(),
                    totalBytes = totalWithParts,
                    doneFiles = 0,
                    totalFiles = totalFiles
                )
            )
        }
        onTick(_state.value)

        val lastTick = AtomicLong(0L)
        fun publish() {
            val now = System.currentTimeMillis()
            val done = doneBytes.get()
            val elapsed = ((now - startedAt) / 1000.0).coerceAtLeast(1.0)
            if (now - lastTick.get() >= 250L || done >= totalWithParts) {
                lastTick.set(now)
                _state.update {
                    it.copy(
                        progress = DownloadProgress(
                            doneBytes = done,
                            totalBytes = totalWithParts,
                            doneFiles = doneFiles.get(),
                            totalFiles = totalFiles,
                            speedBytesPerSecond = (done / elapsed).toLong()
                        )
                    )
                }
                onTick(_state.value)
            }
        }

        val queue = ConcurrentLinkedQueue(missing)
        val failures = java.util.Collections.synchronizedList(ArrayList<String>())
        val workers = minOf(4, maxOf(1, missing.size))

        withContext(Dispatchers.IO) {
            val jobs = (0 until workers).map {
                launch {
                    while (isActive && !cancelRequested) {
                        val pack = queue.poll() ?: break
                        try {
                            downloadWithRetries(pack, dataDir) { added ->
                                doneBytes.addAndGet(added)
                                publish()
                            }
                            doneFiles.incrementAndGet()
                            publish()
                        } catch (cancel: DownloadCancelled) {
                            break
                        } catch (error: Exception) {
                            Log.w(TAG, "Failed to download ${pack.path}", error)
                            failures.add(pack.path)
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
        }

        val cancelled = cancelRequested
        cancelRequested = false
        scanInstalled(index)
        val errorText = when {
            cancelled -> null
            failures.isNotEmpty() -> "${failures.size} file(s) could not be downloaded. Tap download again to resume."
            else -> null
        }
        _state.update {
            it.copy(
                downloading = false,
                cancelling = false,
                progress = null,
                error = errorText,
                message = if (cancelled) "Download cancelled. Your progress has been kept."
                else if (errorText == null) "Offline game data is ready." else null
            )
        }
        onTick(_state.value)
    }

    private fun isInstalled(pack: PackFile, dataDir: File): Boolean {
        val target = File(dataDir, pack.path)
        if (!target.isFile) return false
        return if (pack.size > 0L) target.length() == pack.size else target.length() > 0L
    }

    private suspend fun downloadWithRetries(
        pack: PackFile,
        dataDir: File,
        onBytes: (Long) -> Unit
    ) {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                downloadOne(pack, dataDir, onBytes)
                return
            } catch (cancel: DownloadCancelled) {
                throw cancel
            } catch (error: Exception) {
                lastError = error
                delay(600L * attempt)
            }
        }
        throw lastError ?: IOException("Download failed: ${pack.path}")
    }

    private class DownloadCancelled : Exception()

    private fun downloadOne(pack: PackFile, dataDir: File, onBytes: (Long) -> Unit) {
        val target = File(dataDir, pack.path)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        var offset = if (part.isFile) part.length() else 0L

        val builder = Request.Builder()
            .url(GamePaths.DATA_ORIGIN + "/" + pack.path)
            .header("User-Agent", Net.USER_AGENT)
            .header("Referer", "https://playigi.com/game/")
            .header("Accept", "*/*")
        if (offset > 0L) builder.header("Range", "bytes=$offset-")

        Net.client.newCall(builder.build()).execute().use { response ->
            if (response.code == 200) {
                offset = 0L
            } else if (response.code != 206) {
                throw IOException("HTTP ${response.code} for ${pack.path}")
            }
            val body = response.body ?: throw IOException("Empty body for ${pack.path}")
            FileOutputStream(part, offset > 0L).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelRequested) throw DownloadCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
        }

        if (pack.size > 0L && part.length() != pack.size) {
            // A server/CDN mismatch is rare; only accept a complete non-empty file.
            if (part.length() <= 0L) throw IOException("Empty file for ${pack.path}")
        }
        if (target.exists() && !target.delete()) throw IOException("Cannot replace ${target.name}")
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    fun clearCache() {
        scope.launch {
            val root = GamePaths.root(app)
            root.deleteRecursively()
            val bundled = BundleInstaller.hasBundle(app)
            _state.update {
                it.copy(
                    shellReady = false,
                    installedMissions = emptySet(),
                    onDeviceBytes = 0L,
                    progress = null,
                    bundlePreparing = bundled,
                    bundleProgress = 0f,
                    message = if (bundled) {
                        "Cache cleared. Restoring bundled game data…"
                    } else {
                        "Cached game data cleared."
                    }
                )
            }
            if (bundled) {
                BundleInstaller.prepare(app) { progress ->
                    _state.update { it.copy(bundleProgress = progress.coerceIn(0f, 1f)) }
                }
                _state.update { it.copy(bundlePreparing = false) }
                loadCachedIndex()?.let { index ->
                    _state.update { it.copy(index = index, ready = true) }
                    scanInstalled(index)
                }
            }
        }
    }
}
