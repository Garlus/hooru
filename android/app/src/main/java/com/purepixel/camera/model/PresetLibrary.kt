package com.purepixel.camera.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.LruCache
import com.purepixel.camera.R
import com.purepixel.camera.gl.createPresetColorMatrix
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persistent, device-local preset library. XMP source remains the canonical format. */
class PresetLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("preset_library", Context.MODE_PRIVATE)
    // Merely constructing the camera UI must not touch storage. The directory is
    // created lazily only when the user actually imports a preset.
    private val customDirectory = File(context.filesDir, "lightroom_presets")

    private val packagedSodiumPresets: List<Preset> by lazy {
        context.assets.list(PACKAGED_SODIUM_DIRECTORY).orEmpty().sorted().mapNotNull { fileName ->
            runCatching {
                val number = fileName.removePrefix("GX-02_").removeSuffix(".xmp")
                val id = "sodium_$number"
                val category = CURATED_FILTER_CATEGORIES[id] ?: return@mapNotNull null
                val displayName = if (fileName == "vapor_grain.xmp") "Nightwire Grain" else "Nightwire $number"
                val xmp = context.assets.open("$PACKAGED_SODIUM_DIRECTORY/$fileName")
                    .bufferedReader().use { it.readText() }
                Preset(
                    id = id,
                    name = displayName,
                    accentColor = SODIUM_ACCENTS[number.toIntOrNull()?.rem(SODIUM_ACCENTS.size) ?: 0],
                    lightroom = LightroomPreset.fromXmp(xmp, displayName),
                    category = category
                )
            }.getOrNull()
        }
    }

    private val packagedCommunityPresets: List<Preset> by lazy {
        listOf(PACKAGED_URBAN_DIRECTORY, PACKAGED_AFTERGLOW_DIRECTORY, PACKAGED_WANDERLIGHT_DIRECTORY).flatMap { directory ->
            context.assets.list(directory).orEmpty().sorted().mapNotNull { fileName ->
                runCatching {
                    val id = "${directory.substringAfterLast('/')}_${fileName.removeSuffix(".xmp")}"
                    val category = CURATED_FILTER_CATEGORIES[id] ?: return@mapNotNull null
                    val name = fileName.removeSuffix(".xmp").split('_').joinToString(" ") { word ->
                        word.replaceFirstChar { it.uppercase() }
                    }
                    val xmp = context.assets.open("$directory/$fileName").bufferedReader().use { it.readText() }
                    Preset(
                        id = id,
                        name = name,
                        accentColor = COMMUNITY_ACCENTS[(id.hashCode() and Int.MAX_VALUE) % COMMUNITY_ACCENTS.size],
                        lightroom = LightroomPreset.fromXmp(xmp, name),
                        category = category
                    )
                }.getOrNull()
            }
        }
    }

    /** Lightweight set used for the first frame; packaged XMP files load later. */
    fun startupPresets(): List<Preset> = withDisplayNames(Preset.DEFAULT_PRESETS.map(::applySavedEdits))

    fun builtInPresets(): List<Preset> =
        withDisplayNames((Preset.DEFAULT_PRESETS + packagedSodiumPresets + packagedCommunityPresets).map(::applySavedEdits))

    private fun withDisplayNames(presets: List<Preset>): List<Preset> {
        val categoryCounters = mutableMapOf<PresetCategory, Int>()
        return presets.map { preset ->
            if (preset.isAddButton || preset.id in Preset.FIXED_QUICK_PRESET_IDS) {
                preset
            } else {
                val number = (categoryCounters[preset.category] ?: 0) + 1
                categoryCounters[preset.category] = number
                preset.copy(name = "%s%02d".format(preset.category.code, number))
            }
        }
    }

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
                    accentColor = android.graphics.Color.parseColor("#1C85F3"),
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
        customDirectory.mkdirs()
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
            accentColor = android.graphics.Color.parseColor("#1C85F3"),
            assetPath = File(customDirectory, "$id.xmp").absolutePath,
            lightroom = preset,
            isCustom = true
        )
    }

    fun selectedIds(defaultIds: Set<String>): Set<String> {
        val stored = prefs.getStringSet(KEY_SELECTED, null)?.toSet()
        val migrationsComplete = prefs.getBoolean(KEY_FILTER_LIBRARY_REORGANIZED, false)
        if (stored != null && migrationsComplete) return stored

        var selected = stored ?: defaultIds
        // Remove retired built-ins and fixed processing entries while retaining any
        // deliberately imported filters in the user's selection.
        selected = selected - RETIRED_BUILT_IN_IDS - Preset.FIXED_QUICK_PRESET_IDS
        // Older versions could persist every packaged filter as active. Replace
        // that legacy overflow with the ordered starter selection rather than
        // silently keeping an arbitrary ten from an unordered preference set.
        if (selected.size > 10) selected = defaultIds
        if (selected == PREVIOUS_DEFAULT_SELECTED_PRESET_IDS) selected = defaultIds

        prefs.edit()
            .putStringSet(KEY_SELECTED, selected)
            .putBoolean(KEY_FILTER_LIBRARY_REORGANIZED, true)
            .apply()
        return selected
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

    /** Deletes only imported presets. Built-in looks deliberately remain immutable. */
    fun deleteCustomPreset(preset: Preset) {
        require(preset.isCustom) { "Nur importierte Filter können gelöscht werden." }
        File(customDirectory, "${preset.id}.xmp").delete()
        val recent = prefs.getString(KEY_RECENT, "").orEmpty()
            .split(',').filter { it.isNotBlank() && it != preset.id }
        prefs.edit()
            .remove("$KEY_NAME${preset.id}")
            .remove("$KEY_INTENSITY${preset.id}")
            .remove("$KEY_GRAIN${preset.id}")
            .remove("$KEY_HALATION${preset.id}")
            .putString(KEY_RECENT, recent.joinToString(","))
            .apply()
    }

    fun applySavedEdits(preset: Preset): Preset = preset.copy(
        name = prefs.getString("$KEY_NAME${preset.id}", preset.name).orEmpty().ifBlank { preset.name },
        intensity = prefs.getFloat("$KEY_INTENSITY${preset.id}", preset.intensity).coerceIn(0f, 1f),
        grain = prefs.getFloat("$KEY_GRAIN${preset.id}", preset.grain).coerceIn(0f, 1f),
        halation = prefs.getFloat("$KEY_HALATION${preset.id}", preset.halation).coerceIn(0f, 1f),
        hasUserEdits = prefs.contains("$KEY_NAME${preset.id}") ||
            prefs.contains("$KEY_INTENSITY${preset.id}") ||
            prefs.contains("$KEY_GRAIN${preset.id}") ||
            prefs.contains("$KEY_HALATION${preset.id}")
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
        private const val KEY_FILTER_LIBRARY_REORGANIZED = "filter_library_reorganized_v3"
        private val PREVIOUS_DEFAULT_SELECTED_PRESET_IDS = setOf(
            "sodium_001", "sodium_005", "sodium_006", "sodium_009", "sodium_010",
            "sodium_016", "urban_detroit", "afterglow_fin_de_jornada",
            "wanderlight_way_to_heaven", "silver_push"
        )
        private val RETIRED_BUILT_IN_IDS = setOf(
            "android_processing", "hooru_look", "clean_frame", "soft_daylight", "coastal_clear", "muted_city", "summer_glass",
            "infra_flora", "thermal_bloom", "sodium_004", "urban_adams_tunnel", "urban_arco",
            "urban_capitalinas", "urban_times_square", "afterglow_no_context", "afterglow_the_duo",
            "afterglow_weekend", "wanderlight_cromer_norfolk", "wanderlight_open_road", "wanderlight_paris"
        )
        private val CURATED_FILTER_CATEGORIES = mapOf(
            "sodium_001" to PresetCategory.WARM, "sodium_005" to PresetCategory.WARM,
            "sodium_006" to PresetCategory.WARM, "sodium_009" to PresetCategory.WARM,
            "sodium_010" to PresetCategory.WARM, "sodium_016" to PresetCategory.WARM,
            "urban_detroit" to PresetCategory.WARM, "afterglow_fin_de_jornada" to PresetCategory.WARM,
            "wanderlight_way_to_heaven" to PresetCategory.WARM,

            "sodium_002" to PresetCategory.COLD, "sodium_007" to PresetCategory.COLD,
            "sodium_008" to PresetCategory.COLD, "sodium_011" to PresetCategory.COLD,
            "sodium_017" to PresetCategory.COLD, "sodium_020" to PresetCategory.COLD,
            "urban_london_buslights" to PresetCategory.COLD, "afterglow_rainbow" to PresetCategory.COLD,
            "wanderlight_veli_rat" to PresetCategory.COLD,

            "sodium_003" to PresetCategory.CONTRAST, "sodium_012" to PresetCategory.CONTRAST,
            "sodium_018" to PresetCategory.CONTRAST, "sodium_019" to PresetCategory.CONTRAST,
            "sodium_021" to PresetCategory.CONTRAST, "sodium_022" to PresetCategory.CONTRAST,
            "sodium_vapor_grain" to PresetCategory.CONTRAST, "urban_paris_in_tokyo" to PresetCategory.CONTRAST
        )
        private const val KEY_NAME = "name_"
        private const val KEY_INTENSITY = "intensity_"
        private const val KEY_GRAIN = "grain_"
        private const val KEY_HALATION = "halation_"
        private const val PACKAGED_SODIUM_DIRECTORY = "presets/sodium"
        private const val PACKAGED_URBAN_DIRECTORY = "presets/urban"
        private const val PACKAGED_AFTERGLOW_DIRECTORY = "presets/afterglow"
        private const val PACKAGED_WANDERLIGHT_DIRECTORY = "presets/wanderlight"
        private val SODIUM_ACCENTS = intArrayOf(
            android.graphics.Color.parseColor("#D8752A"),
            android.graphics.Color.parseColor("#B84B7F"),
            android.graphics.Color.parseColor("#4B83AF"),
            android.graphics.Color.parseColor("#875BC1"),
            android.graphics.Color.parseColor("#B98939")
        )
        private val COMMUNITY_ACCENTS = intArrayOf(
            android.graphics.Color.parseColor("#C45D45"),
            android.graphics.Color.parseColor("#667EB4"),
            android.graphics.Color.parseColor("#B46A8C"),
            android.graphics.Color.parseColor("#77986D"),
            android.graphics.Color.parseColor("#BA914B")
        )
    }
}

/** Memory cache for the small, filtered library cards. */
data class FilterPreview(val bitmap: Bitmap, val accentColor: Int)

class FilterPreviewCache(private val context: Context) {
    private val cache = object : LruCache<String, FilterPreview>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: FilterPreview): Int = value.bitmap.allocationByteCount
    }
    private val renderMutex = Mutex()
    private var source: Bitmap? = null

    suspend fun clearMemory() = renderMutex.withLock {
        // Compose may still draw these images while processing the stop event.
        // Drop references without recycling shared bitmaps.
        cache.evictAll()
        source = null
    }
    private val diskCacheDirectory = File(context.cacheDir, "preset_preview_v1")

    private fun cacheKey(preset: Preset) = "${preset.id}:${preset.intensity}:${preset.grain}:${preset.halation}"

    fun invalidate(presetId: String) {
        cache.snapshot().keys
            .filter { it.startsWith("$presetId:") }
            .forEach(cache::remove)
        diskCacheDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith("${presetId.hashCode()}_") }
            .forEach(File::delete)
    }

    suspend fun preview(preset: Preset): FilterPreview {
        val key = cacheKey(preset)
        cache.get(key)?.let { return it }
        return renderMutex.withLock {
            cache.get(key)?.let { return@withLock it }
            // Cache every source in memory; only newly rendered images need a disk write.
            val stored = loadPrebaked(preset) ?: loadFromDisk(preset)
            val preview = stored ?: withContext(Dispatchers.Default) {
                val bitmap = render(preset)
                FilterPreview(bitmap, averageColor(bitmap))
            }
            cache.put(key, preview)
            if (stored == null) saveToDisk(preset, preview)
            preview
        }
    }

    private suspend fun loadPrebaked(preset: Preset): FilterPreview? = withContext(Dispatchers.IO) {
        if (preset.isCustom || preset.hasUserEdits || preset.isAddButton) return@withContext null
        runCatching {
            context.assets.open("preset_previews/${preset.id}.webp").use { stream ->
                BitmapFactory.decodeStream(stream)?.let { bitmap ->
                    FilterPreview(bitmap, averageColor(bitmap))
                }
            }
        }.getOrNull()
    }

    private fun diskFile(preset: Preset): File {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey(preset).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(diskCacheDirectory, "${preset.id.hashCode()}_$fingerprint.webp")
    }

    private suspend fun loadFromDisk(preset: Preset): FilterPreview? = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(diskFile(preset).absolutePath) ?: return@withContext null
        FilterPreview(bitmap, averageColor(bitmap))
    }

    private suspend fun saveToDisk(preset: Preset, preview: FilterPreview) = withContext(Dispatchers.IO) {
        val destination = diskFile(preset)
        val temporary = File(diskCacheDirectory, "${destination.name}.tmp")
        runCatching {
            diskCacheDirectory.mkdirs()
            FileOutputStream(temporary).use { stream ->
                check(preview.bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, stream))
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            trimDiskCache()
        }.onFailure { temporary.delete() }
    }

    private fun trimDiskCache(maxBytes: Long = 48L * 1024 * 1024) {
        val files = diskCacheDirectory.listFiles().orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
        var retained = 0L
        files.forEach { file ->
            retained += file.length()
            if (retained > maxBytes) file.delete()
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
        val source = source ?: requireNotNull(
            BitmapFactory.decodeResource(context.resources, R.drawable.filter_sample)
        ).also { source = it }
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
