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

    private val packagedSodiumPresets: List<Preset> by lazy {
        context.assets.list(PACKAGED_SODIUM_DIRECTORY).orEmpty().sorted().mapNotNull { fileName ->
            runCatching {
                val number = fileName.removePrefix("GX-02_").removeSuffix(".xmp")
                val displayName = if (fileName == "vapor_grain.xmp") "Nightwire Grain" else "Nightwire $number"
                val xmp = context.assets.open("$PACKAGED_SODIUM_DIRECTORY/$fileName")
                    .bufferedReader().use { it.readText() }
                Preset(
                    id = "sodium_$number",
                    name = displayName,
                    accentColor = SODIUM_ACCENTS[number.toIntOrNull()?.rem(SODIUM_ACCENTS.size) ?: 0],
                    lightroom = LightroomPreset.fromXmp(xmp, displayName),
                    category = PresetCategory.CHROMATIC
                )
            }.getOrNull()
        }
    }

    private val packagedCommunityPresets: List<Preset> by lazy {
        listOf(
            PACKAGED_URBAN_DIRECTORY to PresetCategory.URBAN,
            PACKAGED_AFTERGLOW_DIRECTORY to PresetCategory.AFTERGLOW,
            PACKAGED_WANDERLIGHT_DIRECTORY to PresetCategory.WANDERLIGHT
        ).flatMap { (directory, category) ->
            context.assets.list(directory).orEmpty().sorted().mapNotNull { fileName ->
                runCatching {
                    val id = "${category.name.lowercase()}_${fileName.removeSuffix(".xmp")}"
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

    val signatureLooks: List<Preset>
        get() = builtInPresets().filter { it.category == PresetCategory.ESSENTIALS && it.id != "no_filter" }

    fun builtInPresets(): List<Preset> =
        (Preset.DEFAULT_PRESETS + packagedSodiumPresets + packagedCommunityPresets).map(::applySavedEdits)

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
                    accentColor = android.graphics.Color.parseColor("#FF453A"),
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
            accentColor = android.graphics.Color.parseColor("#FF453A"),
            assetPath = File(customDirectory, "$id.xmp").absolutePath,
            lightroom = preset,
            isCustom = true
        )
    }

    fun selectedIds(defaultIds: Set<String>): Set<String> {
        var selected = prefs.getStringSet(KEY_SELECTED, null)?.toSet() ?: defaultIds
        if (!prefs.getBoolean(KEY_FILM_LIBRARY_MIGRATED, false)) {
            selected = (selected - LEGACY_LOOK_IDS) + defaultIds
        }
        if (!prefs.getBoolean(KEY_FIRST_DRAFT_REMOVED, false)) {
            selected -= FIRST_DRAFT_LOOK_IDS
        }
        if (!prefs.getBoolean(KEY_CHROMATIC_REPLACED, false)) {
            selected -= CHROMATIC_DRAFT_LOOK_IDS
        }
        if (!prefs.getBoolean(KEY_NEW_LOOKS_MIGRATED, false)) {
            selected += setOf("hooru_look", "android_processing")
        }

        // Existing installations used to opt every built-in look into the shutter.
        // Trim only that legacy all-selected state; deliberate custom selections stay intact.
        if (!prefs.getBoolean(KEY_TEN_DEFAULTS_MIGRATED, false)) {
            val builtInIds = Preset.DEFAULT_PRESETS.filterNot(Preset::isAddButton).map(Preset::id)
            if (selected.containsAll(builtInIds)) {
                selected -= builtInIds.drop(10).toSet()
            }
        }

        prefs.edit()
            .putStringSet(KEY_SELECTED, selected)
            .putBoolean(KEY_NEW_LOOKS_MIGRATED, true)
            .putBoolean(KEY_TEN_DEFAULTS_MIGRATED, true)
            .putBoolean(KEY_FILM_LIBRARY_MIGRATED, true)
            .putBoolean(KEY_FIRST_DRAFT_REMOVED, true)
            .putBoolean(KEY_CHROMATIC_REPLACED, true)
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
        private const val KEY_TEN_DEFAULTS_MIGRATED = "ten_default_looks_migrated_v1"
        private const val KEY_FILM_LIBRARY_MIGRATED = "film_library_migrated_v1"
        private const val KEY_FIRST_DRAFT_REMOVED = "first_draft_removed_v1"
        private const val KEY_CHROMATIC_REPLACED = "chromatic_replaced_v1"
        private val LEGACY_LOOK_IDS = setOf("leica_mono", "teal_orange", "portra_400", "classic_chrome", "warm_fade", "cool_night", "high_contrast", "cinema_green", "soft_rose")
        private val FIRST_DRAFT_LOOK_IDS = setOf(
            "carbon_mono", "sunlit", "blue_hour", "amber_35", "pastel_negative",
            "dusty_slide", "golden_expiry", "motel_flash", "cine_olive", "cine_log",
            "copper_frame", "sodium_dream", "neon_rain", "velvet_night", "liminal",
            "electric_bloom", "acid_wash"
        )
        private val CHROMATIC_DRAFT_LOOK_IDS = setOf(
            "amber_grid", "violet_void", "chlorine_dream", "arcade_bleed", "cobalt_pull",
            "bleach_milk", "ember_skin"
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
