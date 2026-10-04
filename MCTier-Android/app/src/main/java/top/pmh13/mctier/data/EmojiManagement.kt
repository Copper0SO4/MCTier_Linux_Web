package top.pmh13.mctier.data

data class EmojiRemovalResult(
    val items: List<CustomEmojiItem>,
    val recentIds: List<String>,
)

fun updatedCustomEmojiItems(
    items: List<CustomEmojiItem>,
    categories: List<EmojiCategory>,
    id: String,
    name: String,
    categoryId: String,
): List<CustomEmojiItem>? {
    val clean = name.trim().take(80)
    val target = items.firstOrNull { it.id == id && it.categoryId != "builtin" } ?: return null
    val validDestination = categories.any { it.id == categoryId && it.id !in setOf("recent", "builtin") }
    if (clean.isBlank() || !validDestination) return null
    return items.map { if (it.id == target.id) it.copy(name = clean, categoryId = categoryId) else it }
}

fun removedCustomEmojiReferences(
    items: List<CustomEmojiItem>,
    recentIds: List<String>,
    id: String,
): EmojiRemovalResult? {
    if (items.none { it.id == id && it.categoryId != "builtin" }) return null
    return EmojiRemovalResult(items.filterNot { it.id == id }, recentIds.filterNot { it == id })
}
