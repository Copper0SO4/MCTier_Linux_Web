package top.pmh13.mctier.data

import java.util.Locale

fun validChatAttachment(meta: ChatAttachmentMeta): Boolean =
    meta.id.length in 12..128 && meta.id.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
        meta.name.isNotEmpty() && meta.name.length <= 180 && meta.name !in setOf(".", "..") &&
        meta.name.none { it.isISOControl() || it in "\\/:" } &&
        meta.mime.length in 3..128 && meta.mime.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) &&
        meta.size in 1..ChatMaxAttachmentBytes.toLong()

fun chatAttachmentMime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
    "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "wav" -> "audio/wav"
    "ogg", "oga", "opus" -> "audio/ogg"; "flac" -> "audio/flac"
    "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"
    "pdf" -> "application/pdf"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "doc" -> "application/msword"; "xls" -> "application/vnd.ms-excel"; "ppt" -> "application/vnd.ms-powerpoint"
    "odt" -> "application/vnd.oasis.opendocument.text"; "ods" -> "application/vnd.oasis.opendocument.spreadsheet"; "odp" -> "application/vnd.oasis.opendocument.presentation"
    "rtf" -> "application/rtf"; "csv" -> "text/csv"; "tsv" -> "text/tab-separated-values"
    "txt", "log", "ini", "conf" -> "text/plain"; "md" -> "text/markdown"
    "json" -> "application/json"; "xml" -> "application/xml"; "html", "htm" -> "text/html"
    "js", "ts", "jsx", "tsx", "css", "rs", "kt", "java", "py", "go", "c", "h", "cpp", "hpp", "toml", "yaml", "yml" -> "text/plain"
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "svg" -> "image/svg+xml"; "bmp" -> "image/bmp"
    "zip" -> "application/zip"; "7z" -> "application/x-7z-compressed"; "rar" -> "application/vnd.rar"
    else -> "application/octet-stream"
}

fun chatAttachmentKind(meta: ChatAttachmentMeta): String {
    val ext = meta.name.substringAfterLast('.', "").lowercase(Locale.US)
    return when {
        meta.mime.startsWith("image/") -> "image"
        meta.mime.startsWith("audio/") -> "audio"
        meta.mime.startsWith("video/") -> "video"
        meta.mime == "application/pdf" || ext == "pdf" -> "pdf"
        ext in setOf("doc", "docx", "odt", "rtf") -> "word"
        ext in setOf("xls", "xlsx", "ods", "csv", "tsv") -> "sheet"
        ext in setOf("ppt", "pptx", "odp") -> "slides"
        meta.mime.startsWith("text/") || ext in setOf("json", "xml", "js", "ts", "tsx", "jsx", "css", "rs", "kt", "java", "py", "go", "c", "h", "cpp", "hpp", "toml", "yaml", "yml") -> "text"
        ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst") -> "archive"
        else -> "other"
    }
}
