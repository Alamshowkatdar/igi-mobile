package com.igi.mobile.data

import org.json.JSONObject

data class PackFile(val path: String, val size: Long)

/**
 * Parser for https://data.playigi.com/offline-index.json.
 *
 * The index describes every content-addressed file of the web build, grouped into:
 *  - common : mission data, characters, models, menu/hud/language files (needed by everything)
 *  - sd     : standard-definition textures
 *  - hd     : optional HD texture pack
 *  - touch  : touch-control sprites (phones/tablets)
 *  - flac   : optional lossless audio
 *
 * "shared" lists indexes shared by every mission, and "levels[n]" lists the extra indexes a
 * particular mission needs. That is what lets the app download mission by mission.
 */
class OfflineIndex(
    val files: Map<String, List<PackFile>>,
    val shared: Map<String, IntArray>,
    val levels: Map<Int, Map<String, IntArray>>,
    val bytes: Map<String, Long>
) {

    fun categories(quality: String, touch: Boolean, flac: Boolean): LinkedHashSet<String> {
        val set = LinkedHashSet<String>()
        set.add(COMMON)
        if (quality == HD) {
            // SD is the game's fallback, so install both when HD is requested.
            set.add(SD)
            set.add(HD)
        } else {
            set.add(SD)
        }
        if (touch) set.add(TOUCH)
        if (flac) set.add(FLAC)
        return set
    }

    /** Every file of the build for the selected packs (all missions). */
    fun allFiles(categories: Set<String>): LinkedHashMap<String, PackFile> {
        val out = LinkedHashMap<String, PackFile>()
        for (category in categories) {
            files[category]?.forEach { out.putIfAbsent(it.path, it) }
        }
        return out
    }

    /** Files required by one mission: shared set + that mission's set. */
    fun filesFor(mission: Int, categories: Set<String>): LinkedHashMap<String, PackFile> {
        val out = LinkedHashMap<String, PackFile>()
        for (category in categories) {
            val list = files[category] ?: continue
            val indexes = sortedSetOf<Int>()
            shared[category]?.forEach { indexes.add(it) }
            levels[mission]?.get(category)?.forEach { indexes.add(it) }
            for (index in indexes) {
                val item = list.getOrNull(index) ?: continue
                out.putIfAbsent(item.path, item)
            }
        }
        return out
    }

    fun bytesFor(missions: Set<Int>, categories: Set<String>): Long {
        if (missions.isEmpty()) return allFiles(categories).values.sumOf { it.size }
        val union = LinkedHashMap<String, PackFile>()
        for (mission in missions) filesFor(mission, categories).forEach { (k, v) -> union.putIfAbsent(k, v) }
        return union.values.sumOf { it.size }
    }

    companion object {
        const val COMMON = "common"
        const val SD = "sd"
        const val HD = "hd"
        const val TOUCH = "touch"
        const val FLAC = "flac"
        val CATEGORIES = listOf(COMMON, SD, HD, TOUCH, FLAC)

        fun parse(json: String): OfflineIndex {
            val root = JSONObject(json)
            val filesObject = root.getJSONObject("files")

            val files = LinkedHashMap<String, List<PackFile>>()
            for (category in CATEGORIES) {
                val array = filesObject.optJSONArray(category) ?: continue
                val list = ArrayList<PackFile>(array.length())
                for (i in 0 until array.length()) {
                    val pair = array.optJSONArray(i) ?: continue
                    val path = pair.optString(0)
                    if (path.isBlank()) continue
                    list.add(PackFile(path, pair.optLong(1, 0L)))
                }
                files[category] = list
            }

            val bytes = LinkedHashMap<String, Long>()
            root.optJSONObject("bytes")?.let { object_ ->
                for (category in CATEGORIES) {
                    if (object_.has(category)) bytes[category] = object_.optLong(category, 0L)
                }
            }

            val shared = parseSets(root.optJSONObject("shared"))
            val levels = LinkedHashMap<Int, Map<String, IntArray>>()
            root.optJSONObject("levels")?.let { levelsObject ->
                for (key in levelsObject.keys()) {
                    val number = key.toIntOrNull() ?: continue
                    levels[number] = parseSets(levelsObject.optJSONObject(key))
                }
            }

            return OfflineIndex(files, shared, levels, bytes)
        }

        private fun parseSets(object_: JSONObject?): Map<String, IntArray> {
            if (object_ == null) return emptyMap()
            val out = LinkedHashMap<String, IntArray>()
            for (category in CATEGORIES) {
                val array = object_.optJSONArray(category) ?: continue
                val indexes = IntArray(array.length())
                for (i in 0 until array.length()) indexes[i] = array.optInt(i, -1)
                out[category] = indexes
            }
            return out
        }
    }
}
