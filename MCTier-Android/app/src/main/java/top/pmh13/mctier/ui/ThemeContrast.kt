package top.pmh13.mctier.ui

import kotlin.math.pow

private fun luminance(argb: Int): Double {
    val weights = listOf(.2126, .7152, .0722)
    return listOf(16, 8, 0).mapIndexed { index, shift ->
        val channel = (argb ushr shift and 255) / 255.0
        (if (channel <= .04045) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)) * weights[index]
    }.sum()
}

internal fun contrastRatio(first: Int, second: Int): Double {
    val a = luminance(first)
    val b = luminance(second)
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}

/** Filled controls use a foreground chosen against their fill, not the page. */
internal fun foregroundArgb(fill: Int): Int =
    if (contrastRatio(0xFF000000.toInt(), fill) >= contrastRatio(0xFFFFFFFF.toInt(), fill)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

/** Preserve the user's hue while keeping small accent labels readable. */
internal fun readableAccentArgb(accent: Int, surfaces: List<Int>): Int {
    val target = foregroundArgb(surfaces.first())
    for (step in 0..100) {
        var candidate = 0xFF000000.toInt()
        for (shift in listOf(16, 8, 0)) {
            val from = accent ushr shift and 255
            val to = target ushr shift and 255
            candidate = candidate or ((from + (to - from) * step / 100) shl shift)
        }
        if (surfaces.all { contrastRatio(candidate, it) >= 4.5 }) return candidate
    }
    return target
}
