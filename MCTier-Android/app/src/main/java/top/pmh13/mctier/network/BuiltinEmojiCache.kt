package top.pmh13.mctier.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.pmh13.mctier.data.CustomEmojiItem
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

class BuiltinEmojiCache private constructor(
    private val root: File,
    private val packProvider: () -> InputStream,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "emoji-library-v3"),
        { context.assets.open("builtin-v3.pack.gz") },
    )

    private val cacheDirectory = File(root, "builtin")
    private val marker = File(cacheDirectory, "complete-v3.txt")
    private val syncMutex = Mutex()

    fun cachedItems(): List<CustomEmojiItem> = readIndex(marker).filter { validGif(File(cacheDirectory, "$it.gif")) }.let(::items)

    fun isComplete(): Boolean {
        val ids = readIndex(marker)
        return ids.isNotEmpty() && ids.all { validGif(File(cacheDirectory, "$it.gif")) }
    }

    suspend fun sync(onProgress: (downloaded: Int, total: Int) -> Unit = { _, _ -> }): List<CustomEmojiItem> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val ids = readIndex(marker)
            if ("1f60d" in ids) {
                File(cacheDirectory, "1f60d.gif").delete()
                marker.writeText(ids.filterNot { it == "1f60d" }.joinToString("\n"))
            }
        }
        cachedItems().takeIf { isComplete() }?.let {
            onProgress(it.size, it.size)
            return@withLock it
        }
        withContext(Dispatchers.IO) {
            check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) { "无法创建内置表情缓存目录" }
            marker.delete()
            cacheDirectory.listFiles()?.filter { it.extension == "gif" || it.extension == "part" || it.extension == "tmp" }?.forEach { it.delete() }
            unpack(onProgress)
        }
    }

    private fun unpack(onProgress: (Int, Int) -> Unit): List<CustomEmojiItem> {
        val ids = ArrayList<String>()
        GZIPInputStream(BufferedInputStream(packProvider())).use { input ->
            val magic = input.readExact(Magic.size)
            check(magic.contentEquals(Magic)) { "内置表情资源包版本不兼容" }
            val count = input.readU32().toInt()
            check(count in MinEmojiCount..MaxEmojiCount) { "内置表情数量异常" }
            onProgress(0, count)
            var totalBytes = 0L
            repeat(count) { index ->
                val idLength = input.readU16()
                val assetLength = input.readU32().toInt()
                check(idLength in 1..MaxIdBytes && assetLength in 6..MaxAssetBytes) { "内置表情资源包条目超出限制" }
                totalBytes += assetLength
                check(totalBytes <= MaxTotalBytes) { "内置表情解压后体积超过限制" }
                val id = input.readExact(idLength).toString(Charsets.US_ASCII)
                check(SafeId.matches(id) && id !in ids) { "内置表情 ID 无效或重复" }
                val bytes = input.readExact(assetLength)
                check(hasGifHeader(bytes)) { "内置表情资源包包含无效 GIF" }
                val temporary = File(cacheDirectory, "$id.part")
                temporary.writeBytes(bytes)
                check(replace(temporary, File(cacheDirectory, "$id.gif"))) { "无法提交内置表情缓存" }
                ids += id
                onProgress(index + 1, count)
            }
            check(input.read() == -1) { "内置表情资源包包含多余数据" }
        }
        val temporaryMarker = File(cacheDirectory, "complete-v3.tmp")
        temporaryMarker.writeText(ids.joinToString("\n"))
        check(replace(temporaryMarker, marker)) { "无法提交内置表情索引" }
        return items(ids)
    }

    private fun items(ids: List<String>): List<CustomEmojiItem> = ids.filterNot { it == "1f60d" }.map { id ->
        CustomEmojiItem("builtin-$id", "builtin", id, "image/gif", "builtin/$id.gif", 0L)
    }

    private fun readIndex(file: File): List<String> {
        if (!file.isFile || file.length() !in 1..MaxMarkerBytes) return emptyList()
        val ids = runCatching { file.readLines().filter(String::isNotBlank) }.getOrDefault(emptyList())
        return ids.takeIf { it.size in MinEmojiCount..MaxEmojiCount && it.distinct().size == it.size && it.all { id -> SafeId.matches(id) } }.orEmpty()
    }

    private fun validGif(file: File): Boolean {
        if (!file.isFile || file.length() !in 6..MaxAssetBytes.toLong()) return false
        return runCatching { file.inputStream().use { hasGifHeader(it.readExact(6)) } }.getOrDefault(false)
    }

    private fun replace(source: File, destination: File): Boolean {
        if (destination.exists() && !destination.delete()) return false
        return source.renameTo(destination)
    }

    private fun InputStream.readExact(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(bytes, offset, length - offset)
            check(count >= 0) { "内置表情资源包已损坏" }
            offset += count
        }
        return bytes
    }

    private fun InputStream.readU16(): Int = readExact(2).let { (it[0].toInt() and 255) or ((it[1].toInt() and 255) shl 8) }
    private fun InputStream.readU32(): Long = readExact(4).let { bytes -> bytes.indices.fold(0L) { value, index -> value or ((bytes[index].toLong() and 255) shl (index * 8)) } }

    internal companion object {
        private val Magic = "MCTIER_EMOJI_PACK_V3\u0000".toByteArray(Charsets.US_ASCII)
        private val SafeId = Regex("^[A-Za-z0-9_-]+$")
        private const val MaxAssetBytes = 4 * 1024 * 1024
        private const val MaxTotalBytes = 512L * 1024 * 1024
        private const val MaxMarkerBytes = 128 * 1024L
        private const val MaxIdBytes = 128
        private const val MinEmojiCount = 100
        private const val MaxEmojiCount = 2_000

        internal fun hasGifHeader(bytes: ByteArray): Boolean = bytes.size >= 6 &&
            (bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) || bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray()))

        internal fun fromTestInput(root: File, input: () -> InputStream): BuiltinEmojiCache = BuiltinEmojiCache(root, input)
    }
}
