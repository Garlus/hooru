package com.purepixel.camera.ui

import kotlin.math.abs
import kotlin.math.max

internal data class MagneticZoomResult(
    val zoom: Float,
    val activeMilestone: Float?
)

/**
 * Pulls the zoom progressively towards a physical lens without creating the broad,
 * flat dead zone of a hard snap. A small lock radius keeps the exact lens value
 * stable, while the wider release radius supplies enough hysteresis to avoid chatter.
 */
internal fun magneticZoom(
    rawZoom: Float,
    activeMilestone: Float?,
    milestones: List<Float>
): MagneticZoomResult {
    if (milestones.isEmpty()) return MagneticZoomResult(rawZoom, null)

    fun captureRadius(point: Float): Float = max(.035f, point * .035f).coerceAtMost(.16f)

    val heldMilestone = activeMilestone
        ?.takeIf { held ->
            milestones.any { abs(it - held) < .001f } &&
                abs(rawZoom - held) <= captureRadius(held) * 1.8f
        }
    val nearestMilestone = milestones.minByOrNull { abs(it - rawZoom) }
    val milestone = heldMilestone ?: nearestMilestone?.takeIf {
        abs(rawZoom - it) <= captureRadius(it)
    } ?: return MagneticZoomResult(rawZoom, null)

    val capture = captureRadius(milestone)
    val distance = abs(rawZoom - milestone)
    val lockRadius = capture * .22f
    if (distance <= lockRadius) return MagneticZoomResult(milestone, milestone)

    val releaseRadius = capture * 1.8f
    val proximity = (1f - distance / releaseRadius).coerceIn(0f, 1f)
    val attraction = proximity * proximity * .72f
    return MagneticZoomResult(
        zoom = rawZoom + (milestone - rawZoom) * attraction,
        activeMilestone = milestone
    )
}

