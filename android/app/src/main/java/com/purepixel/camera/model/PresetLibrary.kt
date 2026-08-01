package com.purepixel.camera.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.purepixel.camera.R
import com.purepixel.camera.gl.createPresetColorMatrix
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persistent, device-local preset library. XMP source remains the canonical format. */
class PresetLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("preset_library", Context.MODE_PRIVATE)
    private val customDirectory = File(context.filesDir, "lightroom_presets").apply { mkdirs() }

    val trending: List<Preset>
        get() = Preset.DEFAULT_PRESETS.filterNot { it.isAddButton || it.id == "no_filter" }.take(8)

    fun customPresets(): List<Preset> = customDirectory.listFiles()
        .orEmpty()
        .filter { it.extension.equals("xmp", true) }
        .sortedByDescending(File::lastModified)
        .mapNotNull { file ->
            runCatching {
                val text = file.readText()
                val lightroom = LightroomPreset.fromXmp(text, file.nameWithoutExtension)
                Preset(
                    id = file.nameWithoutExtension,
                    name = lightroom.name,
                    accentColor = android.graphics.Color.parseColor("#8AB4F8"),
                    assetPath = file.absolutePath,
                    lightroom = lightroom,
                    isCustom = true
                )
            }.getOrNull()
        }

    fun importXmp(text: String, fallbackName: String): Preset {
        require(text.contains("<rdf:RDF", ignoreCase = true) || text.contains("<x:xmpmeta", ignoreCase = true)) {
            "Die Datei ist kein gültiges Lightroom-XMP-Preset."
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val id = "user_$hash"
        val preset = LightroomPreset.fromXmp(text, fallbackName)
        File(customDirectory, "$id.xmp").writeText(text)
        markRecent(id)
        return Preset(
            id = id,
            name = preset.name,
            accentColor = android.graphics.Color.parseColor("#8AB4F8"),
            assetPath = File(customDirectory, "$id.xmp").absolutePath,
            lightroom = preset,
            isCustom = true
        )
    }

    fun selectedIds(defaultIds: Set<String>): Set<String> =
        prefs.getStringSet(KEY_SELECTED, null)?.toSet() ?: defaultIds

    fun saveSelected(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED, ids).apply()
    }

    fun recentPresets(all: List<Preset>): List<Preset> {
        val byId = all.associateBy(Preset::id)
        return prefs.getString(KEY_RECENT, "")
            .orEmpty().split(',').filter(String::isNotBlank).mapNotNull(byId::get).take(8)
    }

    fun markRecent(id: String) {
        val updated = buildList {
            add(id)
            addAll(prefs.getString(KEY_RECENT, "").orEmpty().split(',').filter { it.isNotBlank() && it != id })
        }.take(12)
        prefs.edit().putString(KEY_RECENT, updated.joinToString(",")).apply()
    }

    companion object {
        private const val KEY_SELECTED = "selected_ids"
        private const val KEY_RECENT = "recent_ids"
    }
}

/** Memory cache for the small, filtered library cards. */
data class FilterPreview(val bitmap: Bitmap, val accentColor: Int)

class FilterPreviewCache(private val context: Context) {
    private val cache = ConcurrentHashMap<String, FilterPreview>()
    private val renderMutex = Mutex()
    private val source: Bitmap by lazy { BitmapFactory.decodeResource(context.resources, R.drawable.filter_sample) }

    suspend fun preview(preset: Preset): FilterPreview = cache[preset.id] ?: renderMutex.withLock {
        cache[preset.id] ?: withContext(Dispatchers.Default) {
            val bitmap = render(preset)
            FilterPreview(bitmap, averageColor(bitmap)).also { cache[preset.id] = it }
        }
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val step = maxOf(2, minOf(bitmap.width, bitmap.height) / 80)
        var red = 0L; var green = 0L; var blue = 0L; var count = 0L
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            val color = bitmap.getPixel(x, y)
            val r = android.graphics.Color.red(color)
            val g = android.graphics.Color.green(color)
            val b = android.graphics.Color.blue(color)
            red += r; green += g; blue += b; count++
        }
        if (count == 0L) return android.graphics.Color.rgb(180, 180, 180)
        // Keep the outline legible on the dark sheet without changing its hue.
        val r = (red / count).toInt(); val g = (green / count).toInt(); val b = (blue / count).toInt()
        val lift = if (maxOf(r, g, b) < 105) 1.45f else 1f
        return android.graphics.Color.rgb((r*lift).toInt().coerceAtMost(255), (g*lift).toInt().coerceAtMost(255), (b*lift).toInt().coerceAtMost(255))
    }

    private fun render(preset: Preset): Bitmap {
        preset.lightroom?.let { return it.applyToBitmap(source, lutSize = 32) }
        val matrix = createPresetColorMatrix(preset.id) ?: return source
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
        }
    }
}
