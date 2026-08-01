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
        get() = builtInPresets().filterNot { it.isAddButton || it.id == "no_filter" }.take(8)

    fun builtInPresets(): List<Preset> = Preset.DEFAULT_PRESETS.map(::applySavedEdits)

    fun customPresets(): List<Preset> = customDirectory.listFiles()
        .orEmpty()
        .filter { it.extension.equals("xmp", true) }
        .sortedByDescending(File::lastModified)
        .mapNotNull { file ->
            runCatching {
                val text = file.readText()
                val lightroom = LightroomPreset.fromXmp(text, file.nameWithoutExtension)
                applySavedEdits(Preset(
                    id = file.nameWithoutExtension,
                    name = lightroom.name,
                    accentColor = android.graphics.Color.parseColor("#8AB4F8"),
                    assetPath = file.absolutePath,
                    lightroom = lightroom,
                    isCustom = true
                ))
            }.getOrNull()
        }

    fun importXmp(text: String, fallbackName: String): Preset {
        require(text.contains("<rdf:RDF", ignoreCase = true) || text.contains("<x:xmpmeta", ignoreCase = true)) {
            "Die Datei ist kein gültiges Lightroom-XMP-Preset."
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val id = "user_$hash"
        val parsed = LightroomPreset.fromXmp(text, fallbackName)
        val parsedName = parsed.name.ifBlank { fallbackName }
        val firstChoice = uniqueName(parsedName, id)
        // Lightroom collections often reuse their internal XMP name. In that case
        // the file name usually carries the useful differentiator (Warm, Cool, v2…).
        val uniqueName = if (firstChoice != parsedName && fallbackName.isNotBlank()) {
            uniqueName(fallbackName, id)
        } else firstChoice
        val preset = parsed.copy(name = uniqueName)
        File(customDirectory, "$id.xmp").writeText(text)
        saveEdits(
            Preset(
                id = id,
                name = uniqueName,
                lightroom = preset,
                isCustom = true
            )
        )
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

    fun selectedIds(defaultIds: Set<String>): Set<String> {
        val saved = prefs.getStringSet(KEY_SELECTED, null)?.toSet() ?: defaultIds
        if (prefs.getBoolean(KEY_NEW_LOOKS_MIGRATED, false)) return saved
        val migrated = saved + setOf("hooru_look", "android_processing")
        prefs.edit()
            .putStringSet(KEY_SELECTED, migrated)
            .putBoolean(KEY_NEW_LOOKS_MIGRATED, true)
            .apply()
        return migrated
    }

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

    fun saveEdits(preset: Preset) {
        prefs.edit()
            .putString("$KEY_NAME${preset.id}", preset.name.trim())
            .putFloat("$KEY_INTENSITY${preset.id}", preset.intensity.coerceIn(0f, 1f))
            .putFloat("$KEY_GRAIN${preset.id}", preset.grain.coerceIn(0f, 1f))
            .putFloat("$KEY_HALATION${preset.id}", preset.halation.coerceIn(0f, 1f))
            .apply()
    }

    fun applySavedEdits(preset: Preset): Preset = preset.copy(
        name = prefs.getString("$KEY_NAME${preset.id}", preset.name).orEmpty().ifBlank { preset.name },
        intensity = prefs.getFloat("$KEY_INTENSITY${preset.id}", preset.intensity).coerceIn(0f, 1f),
        grain = prefs.getFloat("$KEY_GRAIN${preset.id}", preset.grain).coerceIn(0f, 1f),
        halation = prefs.getFloat("$KEY_HALATION${preset.id}", preset.halation).coerceIn(0f, 1f)
    )

    fun uniqueName(requested: String, excludingId: String? = null): String {
        val base = requested.trim().ifBlank { "Imported Look" }
        val names = (builtInPresets() + customPresets())
            .filterNot { it.id == excludingId }
            .map { it.name.lowercase() }
            .toSet()
        if (base.lowercase() !in names) return base
        var suffix = 2
        while ("$base $suffix".lowercase() in names) suffix++
        return "$base $suffix"
    }

    companion object {
        private const val KEY_SELECTED = "selected_ids"
        private const val KEY_RECENT = "recent_ids"
        private const val KEY_NEW_LOOKS_MIGRATED = "new_looks_migrated_v1"
        private const val KEY_NAME = "name_"
        private const val KEY_INTENSITY = "intensity_"
        private const val KEY_GRAIN = "grain_"
        private const val KEY_HALATION = "halation_"
    }
}

/** Memory cache for the small, filtered library cards. */
data class FilterPreview(val bitmap: Bitmap, val accentColor: Int)

class FilterPreviewCache(private val context: Context) {
    private val cache = ConcurrentHashMap<String, FilterPreview>()
    private val renderMutex = Mutex()
    private val source: Bitmap by lazy { BitmapFactory.decodeResource(context.resources, R.drawable.filter_sample) }

    private fun cacheKey(preset: Preset) = "${preset.id}:${preset.intensity}:${preset.grain}:${preset.halation}"

    fun invalidate(presetId: String) {
        cache.keys.removeAll { it.startsWith("$presetId:") }
    }

    suspend fun preview(preset: Preset): FilterPreview = cache[cacheKey(preset)] ?: renderMutex.withLock {
        cache[cacheKey(preset)] ?: withContext(Dispatchers.Default) {
            val bitmap = render(preset)
            FilterPreview(bitmap, averageColor(bitmap)).also { cache[cacheKey(preset)] = it }
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
        val filtered = preset.lightroom?.applyToBitmap(source, lutSize = 32) ?: run {
            val matrix = createPresetColorMatrix(preset.id) ?: return@run source
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
            }
        }
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            drawBitmap(filtered, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (preset.intensity.coerceIn(0f, 1f) * 255f).toInt()
            })
        }
        if (preset.grain > 0f || preset.halation > 0f) {
            val pixels = IntArray(output.width * output.height)
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            pixels.indices.forEach { index ->
                val color = pixels[index]
                var red = android.graphics.Color.red(color).toFloat()
                var green = android.graphics.Color.green(color).toFloat()
                var blue = android.graphics.Color.blue(color).toFloat()
                val luma = (.2126f * red + .7152f * green + .0722f * blue) / 255f
                val glow = ((luma - .72f) / .28f).coerceIn(0f, 1f) * preset.halation * .24f
                var hash = index * 374761393 + 668265263
                hash = (hash xor (hash ushr 13)) * 1274126177
                val noise = ((hash and 0xffff) / 32767.5f - 1f) * preset.grain * 22f
                red += 255f * glow + noise
                green += 72f * glow + noise
                blue += -32f * glow + noise
                pixels[index] = android.graphics.Color.argb(
                    android.graphics.Color.alpha(color),
                    red.toInt().coerceIn(0, 255),
                    green.toInt().coerceIn(0, 255),
                    blue.toInt().coerceIn(0, 255)
                )
            }
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
        }
        filtered.takeIf { it !== source && it !== output && !it.isRecycled }?.recycle()
        return output
    }
}
