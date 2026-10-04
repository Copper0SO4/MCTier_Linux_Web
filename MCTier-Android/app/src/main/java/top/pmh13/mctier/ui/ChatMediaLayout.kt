package top.pmh13.mctier.ui

internal fun voiceBubbleWidth(seconds: Float): Float =
    144f + (if (seconds.isFinite()) seconds.coerceIn(0f, 10f) else 0f) * 9.6f

internal fun nonEmptySheets(sections: List<String>): List<String> =
    sections.filter { section -> section.lines().drop(1).any { it.isNotBlank() } }
