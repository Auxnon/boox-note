package com.auxnon.booxnote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class CanvasMeta(val id: String, var name: String, val createdAt: Long)

/**
 * Tracks the set of canvases the app knows about (each its own .dpaint-format document + thumb
 * PNG under filesDir/canvases/) and which one was open last, so the app can always come back to
 * where the user left off. The document/thumbnail bytes themselves are read/written by the
 * caller (MainActivity already owns the dpaint JSON codec) - this class only owns the index.
 */
class CanvasStore(context: Context) {
    private val dir = File(context.filesDir, "canvases").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("boox_note_canvases", Context.MODE_PRIVATE)

    fun docFile(id: String): File = File(dir, "$id.json")
    fun thumbFile(id: String): File = File(dir, "${id}_thumb.png")

    fun listCanvases(): List<CanvasMeta> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CanvasMeta>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            out.add(CanvasMeta(id, obj.optString("name", "Canvas"), obj.optLong("createdAt", 0L)))
        }
        return out
    }

    private fun saveIndex(list: List<CanvasMeta>) {
        val arr = JSONArray()
        list.forEach { meta ->
            arr.put(JSONObject().put("id", meta.id).put("name", meta.name).put("createdAt", meta.createdAt))
        }
        prefs.edit().putString(KEY_INDEX, arr.toString()).apply()
    }

    /** Creates a new empty canvas entry (caller still needs to write its blank document/thumb). */
    fun createCanvas(name: String? = null): CanvasMeta {
        val number = prefs.getInt(KEY_NEXT_NUMBER, 1)
        prefs.edit().putInt(KEY_NEXT_NUMBER, number + 1).apply()
        val meta = CanvasMeta(
            id = UUID.randomUUID().toString(),
            name = name?.ifBlank { null } ?: "Canvas $number",
            createdAt = System.currentTimeMillis(),
        )
        saveIndex(listCanvases() + meta)
        return meta
    }

    fun renameCanvas(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val updated = listCanvases().map { if (it.id == id) it.copy(name = trimmed) else it }
        saveIndex(updated)
    }

    fun lastOpenId(): String? = prefs.getString(KEY_LAST_OPEN_ID, null)

    fun setLastOpenId(id: String) {
        prefs.edit().putString(KEY_LAST_OPEN_ID, id).apply()
    }

    fun saveThumbnail(id: String, bitmap: Bitmap) {
        runCatching {
            val maxDim = 320
            val scale = (maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                bitmap
            }
            FileOutputStream(thumbFile(id)).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun loadThumbnail(id: String): Bitmap? {
        val file = thumbFile(id)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    companion object {
        private const val KEY_INDEX = "index"
        private const val KEY_LAST_OPEN_ID = "last_open_id"
        private const val KEY_NEXT_NUMBER = "next_number"
    }
}
