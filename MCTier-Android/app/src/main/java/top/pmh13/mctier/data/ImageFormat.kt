package top.pmh13.mctier.data

const val ChatImageMaxBytes = 2 * 1024 * 1024

fun sniffChatImageMime(bytes: ByteArray): String? {
    if (bytes.size >= 6) {
        val signature = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (signature == "GIF87a" || signature == "GIF89a") return "image/gif"
    }
    if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte()
        && bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() && bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte()) return "image/png"
    if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return "image/jpeg"
    if (bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF"
        && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") return "image/webp"
    return null
}

fun imageExtension(mime: String): String = when (mime) {
    "image/gif" -> "gif"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "jpg"
}
