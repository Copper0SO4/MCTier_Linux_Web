package top.pmh13.mctier.data

// Keep the wire format identical to builtinEmojiMessage.ts on desktop.
object BuiltinEmojiMessage {
    private const val prefix = "mctier:emoji:v3:"
    private val idPattern = Regex("builtin-[A-Za-z0-9_-]{1,128}")

    fun encode(id: String): String {
        require(idPattern.matches(id)) { "Invalid built-in emoji ID" }
        return prefix + canonicalId(id)
    }

    fun decode(content: String): String? = content.takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)?.takeIf { idPattern.matches(it) }?.let(::canonicalId)

    private fun canonicalId(id: String): String = if (id == "builtin-1f60d") "builtin-1f970" else id
}
