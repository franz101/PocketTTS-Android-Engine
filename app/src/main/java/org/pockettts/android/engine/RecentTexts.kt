package org.pockettts.android.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class RecentItem(
    val id: String,
    val title: String,
    val sentenceIndex: Int,
    val sentenceCount: Int,
    val updatedAt: Long,
    val hash: Int
) {
    /** Listening progress in 0..1. */
    val progress: Float
        get() = if (sentenceCount <= 0) 0f else (sentenceIndex.toFloat() / sentenceCount).coerceIn(0f, 1f)
}

/**
 * The "Continue listening" list. Metadata lives in SharedPreferences as JSON; each text is
 * stored in its own private file so long texts never pass through preferences or intents.
 */
internal object RecentTexts {
    private const val PREFS = "pockettts_recent"
    private const val KEY = "items.v1"
    const val MAX_ITEMS = 5
    const val MAX_CHARS = 200_000

    fun list(context: Context): List<RecentItem> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RecentItem(
                    o.getString("id"),
                    o.getString("title"),
                    o.optInt("index", 0),
                    o.optInt("count", 0),
                    o.optLong("updated", 0L),
                    o.optInt("hash", 0)
                )
            }.filter { file(context, it.id).isFile }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context, id: String): RecentItem? = list(context).firstOrNull { it.id == id }

    /** Stores [text] (capped at [MAX_CHARS]) and moves it to the top; identical texts are reused. */
    fun add(context: Context, title: String, text: String): RecentItem {
        val capped = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        val hash = capped.hashCode()
        val items = list(context).toMutableList()
        val existing = items.firstOrNull { it.hash == hash && readText(context, it.id) == capped }
        val item = existing?.copy(updatedAt = System.currentTimeMillis())
            ?: RecentItem(UUID.randomUUID().toString(), title, 0, 0, System.currentTimeMillis(), hash)
        if (existing == null) file(context, item.id).apply { parentFile?.mkdirs() }.writeText(capped)
        items.removeAll { it.id == item.id }
        items.add(0, item)
        save(context, items)
        return item
    }

    fun readText(context: Context, id: String): String? =
        runCatching { file(context, id).takeIf { it.isFile }?.readText() }.getOrNull()

    fun updateProgress(context: Context, id: String, index: Int, count: Int) {
        val items = list(context).map {
            if (it.id == id) it.copy(sentenceIndex = index, sentenceCount = count, updatedAt = System.currentTimeMillis()) else it
        }
        save(context, items.sortedByDescending { it.updatedAt })
    }

    fun remove(context: Context, id: String) {
        file(context, id).delete()
        save(context, list(context).filter { it.id != id })
    }

    private fun save(context: Context, items: List<RecentItem>) {
        val kept = items.take(MAX_ITEMS)
        items.drop(MAX_ITEMS).forEach { file(context, it.id).delete() }
        val array = JSONArray()
        kept.forEach {
            array.put(
                JSONObject().put("id", it.id).put("title", it.title).put("index", it.sentenceIndex)
                    .put("count", it.sentenceCount).put("updated", it.updatedAt).put("hash", it.hash)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun file(context: Context, id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "Invalid recent id" }
        return File(File(context.filesDir, "recent"), "$id.txt")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
