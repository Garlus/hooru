package com.purepixel.camera.tracking

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.exp

/** Deterministic synthetic image sequences; runs without an Android device. */
fun main() {
    val width = 160
    val height = 212
    fun image(cx: Int, cy: Int, gain: Float = 1f, bias: Float = 0f, scale: Float = 1f): FloatArray =
        FloatArray(width * height) { index ->
            val x = (index % width - cx) / scale
            val y = (index / width - cy) / scale
            val value = if (abs(x) < 18 && abs(y) < 18)
                70f + 110f * exp(-((x - 3) * (x - 3) / 18 + (y + 2) * (y + 2) / 35)) +
                    70f * exp(-((x + 5) * (x + 5) / 8 + (y - 4) * (y - 4) / 12)) +
                    20f * sin(x * .6f + y * .3f + x * y * .11f)
                else 30f
            value * gain + bias
        }
    var time = 1_000_000_000L
    fun next() = time.also { time += 83_000_000L }
    val tracker = SubjectTracker()
    tracker.select(.5f, .5f)
    check(tracker.process(image(80, 106), width, height, next()).box != null)
    val start = System.nanoTime()
    repeat(12) { step ->
        val x = 80 + (step + 1) * 2
        val y = 106 - step - 1
        val state = tracker.process(image(x, y, .7f, 35f), width, height, next())
        val box = checkNotNull(state.box) { "Translation/brightness failed at $step: $state" }
        check(abs(box.x * width - x) < 2f && abs(box.y * height - y) < 2f) { "Wrong target: $box" }
    }
    println("Translation + exposure change passed; mean frame ${(System.nanoTime() - start) / 12 / 1_000_000} ms")
    repeat(2) {
        val state = tracker.process(FloatArray(width * height) { 100f }, width, height, next())
        check(state.box == null && !state.lost && state.searching)
    }
    check(tracker.process(FloatArray(width * height) { 100f }, width, height, next()).lost)
    check(tracker.process(image(80, 106), width, height, next()).box == null) { "Must not silently reacquire" }
    println("Occlusion/loss and no automatic target switching passed")

    tracker.select(.5f, .5f)
    check(tracker.process(FloatArray(width * height) { 100f }, width, height, next()).lost)
    println("Textureless selection rejected")

    tracker.select(.5f, .5f)
    tracker.process(image(80, 106), width, height, next())
    repeat(4) { step ->
        val state = tracker.process(image(80, 106, scale = 1f + (step + 1) * .04f), width, height, next())
        check(state.box != null) { "Scale tracking failed: $state" }
    }
    println("Gradual scale change passed")

    tracker.select(.25f, .25f)
    val reselection = tracker.process(image(40, 53), width, height, next())
    check(abs(checkNotNull(reselection.box).x - .25f) < .01f)
    tracker.reset()
    check(tracker.process(image(40, 53), width, height, next()).box == null)
    tracker.select(.5f, .5f)
    tracker.process(image(80, 106), width, height, next())
    time += 1_000_000_000L
    check(tracker.process(image(80, 106), width, height, next()).lost)
    println("Reselection, cancellation and stale-frame timeout passed")

    tracker.select(.5f, .5f)
    tracker.process(image(80, 106), width, height, next())
    check(tracker.process(FloatArray(width * height) { 40f }, width, height, next()).searching)
    check(tracker.process(image(84, 108), width, height, next()).box != null)
    println("Brief occlusion recovery passed")

    tracker.select(.5f, .5f)
    val repeated = FloatArray(width * height) { 120f + 90f * sin((it % width) * .8f) }
    tracker.process(repeated, width, height, next())
    repeat(2) { check(tracker.process(repeated, width, height, next()).searching) }
    check(tracker.process(repeated, width, height, next()).lost)
    println("Ambiguous repeated patterns rejected")

    for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
        tracker.select(x, y)
        val textured = FloatArray(width * height) { (it * 13 % 255).toFloat() }
        val state = tracker.process(textured, width, height, next())
        val box = checkNotNull(state.box)
        check(box.x - box.width / 2 >= 0 && box.y - box.height / 2 >= 0)
        check(box.x + box.width / 2 <= 1 && box.y + box.height / 2 <= 1)
    }
    println("All four image edges passed")
}
