package com.purepixel.camera.model

import android.graphics.Color

enum class ProcessingMode {
    NATURAL,
    HOORU
}

enum class PresetCategory(val label: String, val code: String) {
    WARM("Warm", "WA"),
    COLD("Cold", "CO"),
    CONTRAST("Contrast", "CTR")
}

data class Preset(
    val id: String,
    val name: String,
    val accentColor: Int = Color.parseColor("#ff3b30"),
    val assetPath: String? = null,
    val lightroom: LightroomPreset? = null,
    val isCustom: Boolean = false,
    val isAddButton: Boolean = false,
    val intensity: Float = 1f,
    val grain: Float = 0f,
    val halation: Float = 0f,
    val category: PresetCategory = PresetCategory.WARM,
    val processingMode: ProcessingMode = ProcessingMode.HOORU,
    val hasUserEdits: Boolean = false
) {
    companion object {
        val FIXED_QUICK_PRESET_IDS = setOf("no_filter")

        /** The initial quick selection. The unfiltered base option is always separate. */
        val DEFAULT_SELECTED_PRESET_IDS = linkedSetOf(
            // CO01, CO04, CO06, CO09
            "sodium_002", "sodium_011", "sodium_020", "wanderlight_veli_rat",
            // WA01, WA02, WA05
            "silver_push", "sodium_001", "sodium_009",
            // CTR05
            "sodium_019"
        )

        val DEFAULT_PRESETS = listOf(
            Preset(
                "no_filter",
                "Null Processing",
                Color.parseColor("#d7d7d7"),
                category = PresetCategory.CONTRAST,
                processingMode = ProcessingMode.NATURAL
            ),
            Preset("silver_push", "Silver Push", Color.parseColor("#D6D0C5"), category = PresetCategory.WARM, intensity = 1f, grain = .58f),
            Preset("noir_halide", "Noir Halide", Color.parseColor("#9699A0"), category = PresetCategory.CONTRAST, intensity = 1f, grain = .76f),

            Preset("add_new", "+", Color.parseColor("#8e8e93"), isAddButton = true)
        )
    }
}
