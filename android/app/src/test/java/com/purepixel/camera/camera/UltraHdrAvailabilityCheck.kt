package com.purepixel.camera.camera

fun main() {
    val availability = UltraHdrAvailability()
    val jpeg = UltraHdrAvailability.Configuration("wide", "JPG", "STANDARD_12_MP")
    val rawAndJpeg = jpeg.copy(captureFormat = "RAW+JPG")
    check(availability.canAttempt(rawAndJpeg))
    availability.reject(rawAndJpeg)
    check(!availability.canAttempt(rawAndJpeg)) { "Rejected combination must fall back without a retry loop" }
    check(availability.canAttempt(jpeg)) { "RAW+JPG failure must not disable ordinary Ultra HDR photos" }
    availability.reject(jpeg)
    check(!availability.canAttempt(jpeg))
    check(availability.canAttempt(jpeg.copy(cameraId = "tele"))) { "Other lenses must retain HDR" }
    check(availability.canAttempt(jpeg.copy(resolution = "FULL_SENSOR"))) { "Other output sizes must retain HDR" }
    check(!availability.canAttempt(jpeg.copy())) { "Returning to a rejected combination must retain its fallback" }
    println("Ultra HDR fallback regression checks passed")
}
