package top.pmh13.mctier.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private val accents = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFF0000, 0xFF0000FF, 0xFFFFFF00, 0xFF52C41A, 0xFF777777).map { it.toInt() }

    @Test fun filledControlsKeepReadableTextForCustomAccents() {
        accents.forEach { fill -> assertTrue(contrastRatio(foregroundArgb(fill), fill) >= 4.5) }
    }

    @Test fun accentLabelsRemainReadableAfterLightAndDarkSwitches() {
        val light = listOf(0xFFFFFFFF, 0xFFE9ECF3, 0xFFEDEFF5, 0xFFF7F8FC).map { it.toInt() }
        val dark = listOf(0xFF20202F, 0xFF2B2B40, 0xFF121220, 0xFF1C1C2A).map { it.toInt() }
        listOf(light, dark, light).forEach { surfaces ->
            accents.forEach { accent ->
                val foreground = readableAccentArgb(accent, surfaces)
                surfaces.forEach { assertTrue(contrastRatio(foreground, it) >= 4.5) }
            }
        }
    }
}
