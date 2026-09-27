package com.purepixel.camera.camera

/** A rejected stream combination says nothing about another lens or RAW/JPEG combination. */
internal class UltraHdrAvailability {
    data class Configuration(val cameraId: String, val captureFormat: String, val resolution: String)

    private val rejected = mutableSetOf<Configuration>()

    fun canAttempt(configuration: Configuration): Boolean = configuration !in rejected

    fun reject(configuration: Configuration) {
        rejected += configuration
    }
}
