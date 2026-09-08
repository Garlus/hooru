package com.purepixel.camera.tracking

import kotlin.math.*

/** A visual patch tracker, independent of Android and of semantic object classes. */
data class TrackingBox(val x: Float, val y: Float, val width: Float, val height: Float)
data class TrackingState(val box: TrackingBox? = null, val lost: Boolean = false, val searching: Boolean = false)

class SubjectTracker {
    private var template: FloatArray? = null
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 10f
    private var frameWidth = 0
    private var frameHeight = 0
    private var misses = 0
    private var lastFrameNanos = 0L
    private var selectionX = 0f
    private var selectionY = 0f
    private var pending = false

    fun select(x: Float, y: Float) {
        reset()
        selectionX = x.coerceIn(0f, 1f)
        selectionY = y.coerceIn(0f, 1f)
        pending = true
    }

    fun reset() {
        template = null
        pending = false
        misses = 0
        lastFrameNanos = 0L
    }

    fun process(pixels: FloatArray, width: Int, height: Int, timestampNanos: Long): TrackingState {
        require(pixels.size == width * height && width >= 32 && height >= 32)
        if (pending) {
            pending = false
            frameWidth = width
            frameHeight = height
            radius = min(width, height) * .065f
            centerX = (selectionX * width).coerceIn(radius, width - 1f - radius)
            centerY = (selectionY * height).coerceIn(radius, height - 1f - radius)
            val patch = sample(pixels, width, centerX, centerY, radius)
            if (!normalize(patch)) return lost()
            template = patch
            lastFrameNanos = timestampNanos
            return TrackingState(box())
        }
        val reference = template ?: return TrackingState()
        if (width != frameWidth || height != frameHeight ||
            timestampNanos - lastFrameNanos > 600_000_000L) return lost()
        lastFrameNanos = timestampNanos
        val search = (min(width, height) * .12f).roundToInt()
        var best = -1f
        var bestX = centerX
        var bestY = centerY
        var bestRadius = radius
        val candidates = ArrayList<FloatArray>()
        val scratch = FloatArray(SAMPLES * SAMPLES)
        // Fixed appearance reference prevents gradual drift onto the background.
        for (scale in floatArrayOf(.94f, 1f, 1.06f)) {
            val r = (radius * scale).coerceIn(min(width, height) * .04f, min(width, height) * .12f)
            for (dy in -search..search step 2) for (dx in -search..search step 2) {
                val x = centerX + dx
                val y = centerY + dy
                if (x < r || y < r || x + r >= width || y + r >= height) continue
                sample(pixels, width, x, y, r, scratch)
                val score = correlation(reference, scratch)
                candidates.add(floatArrayOf(x, y, score, r))
                if (score > best) { best = score; bestX = x; bestY = y; bestRadius = r }
            }
        }
        // Resolve the last pixel after the coarse search.
        val coarseX = bestX
        val coarseY = bestY
        for (dy in -1..1) for (dx in -1..1) {
            val x = coarseX + dx
            val y = coarseY + dy
            if (x < bestRadius || y < bestRadius || x + bestRadius >= width || y + bestRadius >= height) continue
            sample(pixels, width, x, y, bestRadius, scratch)
            val score = correlation(reference, scratch)
            if (score > best) { best = score; bestX = x; bestY = y }
        }
        val rivalCandidate = candidates.filter { hypot(it[0] - bestX, it[1] - bestY) > radius }
            .maxByOrNull { it[2] }
        var rival = rivalCandidate?.get(2) ?: -1f
        // Refine the competing peak too; comparing a refined best match against a
        // coarse rival would falsely accept stripes and other repeated patterns.
        if (rivalCandidate != null) {
            val r = rivalCandidate[3]
            for (dy in -1..1) for (dx in -1..1) {
                val x = rivalCandidate[0] + dx
                val y = rivalCandidate[1] + dy
                if (x < r || y < r || x + r >= width || y + r >= height ||
                    hypot(x - bestX, y - bestY) <= radius) continue
                sample(pixels, width, x, y, r, scratch)
                rival = maxOf(rival, correlation(reference, scratch))
            }
        }
        if (best < .78f || best - rival < .07f) {
            misses++
            return if (misses >= 3) lost() else TrackingState(searching = true)
        }
        misses = 0
        centerX = bestX
        centerY = bestY
        radius = bestRadius
        return TrackingState(box())
    }

    private fun box() = TrackingBox(centerX / frameWidth, centerY / frameHeight,
        2 * radius / frameWidth, 2 * radius / frameHeight)

    private fun lost(): TrackingState {
        reset()
        return TrackingState(lost = true)
    }

    private fun sample(pixels: FloatArray, width: Int, x: Float, y: Float, r: Float,
                       output: FloatArray = FloatArray(SAMPLES * SAMPLES)): FloatArray {
        for (row in 0 until SAMPLES) for (column in 0 until SAMPLES) {
            val px = x - r + 2 * r * column / (SAMPLES - 1)
            val py = y - r + 2 * r * row / (SAMPLES - 1)
            val ix = px.toInt().coerceIn(0, width - 2)
            val iy = py.toInt().coerceIn(0, pixels.size / width - 2)
            val fx = (px - ix).coerceIn(0f, 1f)
            val fy = (py - iy).coerceIn(0f, 1f)
            output[row * SAMPLES + column] =
                (pixels[iy * width + ix] * (1 - fx) + pixels[iy * width + ix + 1] * fx) * (1 - fy) +
                (pixels[(iy + 1) * width + ix] * (1 - fx) + pixels[(iy + 1) * width + ix + 1] * fx) * fy
        }
        return output
    }

    private fun normalize(values: FloatArray): Boolean {
        val mean = values.average().toFloat()
        var energy = 0f
        for (i in values.indices) { values[i] -= mean; energy += values[i] * values[i] }
        if (energy / values.size < 36f) return false
        val norm = sqrt(energy)
        for (i in values.indices) values[i] /= norm
        return true
    }

    private fun correlation(reference: FloatArray, patch: FloatArray): Float {
        if (!normalize(patch)) return -1f
        var score = 0f
        for (i in reference.indices) score += reference[i] * patch[i]
        return score
    }

    companion object { private const val SAMPLES = 17 }
}
