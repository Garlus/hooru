package com.purepixel.camera.ui

fun main() {
    // Real focal stops need not be exact hundredths of the base lens ratio.
    val tele = 75f / 26f
    val stops = listOf(1f, 2f, tele)
    for (delta in listOf(-.005f, 0f, .005f)) {
        val result = magneticZoom(tele + delta, null, stops)
        check(result.zoom == tele) { "Native lens stop must stay exact: $result" }
        check(result.activeMilestone == tele)
    }
    val captured = magneticZoom(tele + .04f, null, stops)
    check(captured.activeMilestone == tele)
    check(captured.zoom in tele..(tele + .04f))
    val held = magneticZoom(tele + .12f, tele, stops)
    check(held.activeMilestone == tele) { "Hysteresis should retain the selected lens" }
    val released = magneticZoom(tele + .3f, tele, stops)
    check(released.activeMilestone == null)
    check(released.zoom == tele + .3f)
    check(magneticZoom(1.5f, null, emptyList()).zoom == 1.5f)
    println("Zoom interaction checks passed")
}
