package com.purepixel.camera.model

import android.graphics.Color

enum class ProcessingMode {
    NATURAL,
    HOORU,
    ANDROID
}

enum class PresetCategory(val label: String) {
    ESSENTIALS("Signature & Clean"),
    MONO("Einfarbig."),
    CHROMATIC("Chromatic Chaos"),
    URBAN("Urban Stories"),
    AFTERGLOW("Afterglow"),
    WANDERLIGHT("Wanderlight")
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
    val category: PresetCategory = PresetCategory.ESSENTIALS,
    val processingMode: ProcessingMode = ProcessingMode.HOORU
) {
    companion object {
        val DEFAULT_PRESETS = listOf(
            Preset(
                "no_filter",
                "Natural",
                Color.parseColor("#d7d7d7"),
                processingMode = ProcessingMode.NATURAL
            ),
            Preset("hooru_look", "Hooru Look", Color.parseColor("#D9503F"), intensity = .82f),
            Preset(
                "android_processing",
                "Android Processing",
                Color.parseColor("#8AB4F8"),
                processingMode = ProcessingMode.ANDROID
            ),
            Preset("clean_frame", "Clean Frame", Color.parseColor("#9CAFA6"), intensity = .72f, grain = .03f),
            Preset("soft_daylight", "Soft Daylight", Color.parseColor("#C7A17C"), intensity = .70f, grain = .05f),
            Preset("coastal_clear", "Coastal Clear", Color.parseColor("#6A9A9A"), intensity = .76f, grain = .03f),
            Preset("muted_city", "Muted City", Color.parseColor("#8B8990"), intensity = .78f, grain = .06f),
            Preset("summer_glass", "Summer Glass", Color.parseColor("#C2A75B"), intensity = .74f, grain = .04f),

            Preset("silver_push", "Silver Push", Color.parseColor("#D6D0C5"), category = PresetCategory.MONO, intensity = 1f, grain = .58f),
            Preset("noir_halide", "Noir Halide", Color.parseColor("#9699A0"), category = PresetCategory.MONO, intensity = 1f, grain = .76f),

            Preset("infra_flora", "Infra Flora", Color.parseColor("#D64A68"), category = PresetCategory.MONO, intensity = 1f, grain = .62f, halation = .20f),
            Preset("thermal_bloom", "Thermal Bloom", Color.parseColor("#C84C9A"), category = PresetCategory.MONO, intensity = 1f, grain = .72f, halation = .34f),

            Preset("add_new", "+", Color.parseColor("#8e8e93"), isAddButton = true)
        )
    }
}
