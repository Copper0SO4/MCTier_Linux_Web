package top.pmh13.mctier.data

import org.json.JSONObject

data class MessagePreview(val kind: String, val text: String, val detail: String = "")

fun messagePreview(message: ChatMessage): MessagePreview {
    if (message.recalled) return MessagePreview("text", "[消息已撤回]")
    if (message.type == "text" && BuiltinEmojiMessage.decode(message.content) != null) return MessagePreview("image", "[内置表情]")
    return when (message.type) {
        "text" -> MessagePreview("text", message.content.replaceFirst(Regex("^> \\[reply:[^]]+]\\s*"), "> "))
        "image" -> MessagePreview("image", "[图片 / 表情]")
        "voice" -> {
            val duration = runCatching { JSONObject(message.content).optDouble("duration", 0.0) }.getOrDefault(0.0)
            MessagePreview("voice", "语音消息", if (duration.isFinite() && duration > 0 && duration <= 120) "${kotlin.math.ceil(duration).toInt()} 秒" else "")
        }
        "file" -> {
            val file = message.attachment?.takeIf(::validChatAttachment) ?: return MessagePreview("file", "[附件不可用]")
            val kind = chatAttachmentKind(file).takeIf { it in setOf("image", "video", "audio") } ?: "file"
            val size = when { file.size < 1024 -> "${file.size} B"; file.size < 1048576 -> "${file.size / 1024} KB"; else -> "${file.size / 1048576} MB" }
            MessagePreview(kind, file.name, "${file.name.substringAfterLast('.', "FILE").uppercase()} · $size")
        }
        else -> MessagePreview("file", "[不支持的消息]")
    }
}
