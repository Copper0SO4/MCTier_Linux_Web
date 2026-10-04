package top.pmh13.mctier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.pmh13.mctier.data.CustomEmojiItem
import top.pmh13.mctier.data.EmojiCategory
import top.pmh13.mctier.data.removedCustomEmojiReferences
import top.pmh13.mctier.data.updatedCustomEmojiItems

class EmojiManagementTest {
    private val categories = listOf(
        EmojiCategory("recent", "最近", true),
        EmojiCategory("builtin", "内置", true),
        EmojiCategory("custom", "自定义", true),
        EmojiCategory("animals", "动物"),
    )
    private val custom = CustomEmojiItem("custom-1", "custom", "Original", "image/gif", "custom-1.gif", 1)
    private val builtin = CustomEmojiItem("builtin-1", "builtin", "Built in", "image/gif", "builtin/1.gif", 0)

    @Test
    fun customEmojiCanBeRenamedAndMovedOnlyToUserDestinations() {
        val updated = updatedCustomEmojiItems(listOf(custom, builtin), categories, custom.id, "  Renamed  ", "animals")
        assertEquals("Renamed", updated?.first()?.name)
        assertEquals("animals", updated?.first()?.categoryId)
        assertNull(updatedCustomEmojiItems(listOf(custom), categories, custom.id, "Name", "builtin"))
        assertNull(updatedCustomEmojiItems(listOf(builtin), categories, builtin.id, "Name", "custom"))
    }

    @Test
    fun deletingCustomEmojiRemovesRecentReferenceButProtectsBuiltInEmoji() {
        val removed = removedCustomEmojiReferences(listOf(custom, builtin), listOf(custom.id, builtin.id), custom.id)
        assertEquals(listOf(builtin), removed?.items)
        assertEquals(listOf(builtin.id), removed?.recentIds)
        assertNull(removedCustomEmojiReferences(listOf(builtin), listOf(builtin.id), builtin.id))
    }
}
