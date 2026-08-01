package com.purepixel.camera.model

import android.graphics.Color

data class Preset(
    val id: String,
    val name: String,
    val accentColor: Int = Color.parseColor("#ff3b30"),
    val assetPath: String? = null,
    val lightroom: LightroomPreset? = null,
    val isCustom: Boolean = false,
    val isAddButton: Boolean = false
) {
    companion object {
        val DEFAULT_PRESETS = listOf(
            Preset("leica_mono", "Leica Mono", Color.parseColor("#ffffff")),
            Preset("teal_orange", "Teal & Orange", Color.parseColor("#3b82f6")),
            Preset("no_filter", "No Filter", Color.parseColor("#ff3b30")), // Default centered (3rd option)
            Preset("portra_400", "Portra 400", Color.parseColor("#ff9500")),
            Preset("classic_chrome", "Fuji Chrome", Color.parseColor("#34c759")),
            Preset("warm_fade", "Warm Fade", Color.parseColor("#ffcc80")),
            Preset("cool_night", "Cool Night", Color.parseColor("#5ac8fa")),
            Preset("high_contrast", "High Contrast", Color.parseColor("#af52de")),
            Preset("cinema_green", "Cinema Green", Color.parseColor("#30d158")),
            Preset("soft_rose", "Soft Rose", Color.parseColor("#ff6482")),
            Preset("add_new", "+", Color.parseColor("#8e8e93"), isAddButton = true)
        )
    }
}
