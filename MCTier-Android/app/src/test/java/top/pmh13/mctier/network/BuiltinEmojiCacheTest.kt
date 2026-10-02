package top.pmh13.mctier.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class BuiltinEmojiCacheTest {
    @Test fun oldCacheRemovesDuplicateWithoutDownloadingOrLosingOtherEmoji() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-dedup-test").toFile()
        val ids = (0 until 100).map { "id$it" } + listOf("1f60d", "1f970")
        val directory = java.io.File(root, "builtin").apply { mkdirs() }
        try {
            ids.forEach { java.io.File(directory, "$it.gif").writeBytes(gifBytes()) }
            java.io.File(directory, "complete-v3.txt").writeText(ids.joinToString("\n"))
            val cache = BuiltinEmojiCache.fromTestInput(root) { error("Should reuse cached emoji") }
            assertFalse(cache.cachedItems().any { it.id == "builtin-1f60d" })
            val items = cache.sync()
            assertEquals(101, items.size)
            assertTrue(items.any { it.id == "builtin-1f970" })
            assertFalse(java.io.File(directory, "1f60d.gif").exists())
            assertEquals(items, cache.sync())
        } finally { root.deleteRecursively() }
    }
    @Test fun startupAndPickerShareOneExtraction() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-concurrent-test").toFile()
        val pack = packOf((0 until 100).map { "id$it" to gifBytes() })
        val opens = java.util.concurrent.atomic.AtomicInteger()
        try {
            val cache = BuiltinEmojiCache.fromTestInput(root) { opens.incrementAndGet(); ByteArrayInputStream(pack) }
            val results = List(3) { async { cache.sync() } }.awaitAll()
            assertEquals(1, opens.get())
            assertTrue(results.all { it == results.first() })
            assertEquals(100, cache.cachedItems().size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun unpackCreatesGifCacheAndSecondSyncReusesMarker() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-pack-test").toFile()
        var opens = 0
        val pack = packOf((0 until 100).map { "id$it" to gifBytes() })
        try {
            val cache = BuiltinEmojiCache.fromTestInput(root) { opens += 1; ByteArrayInputStream(pack) }
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(100, cache.sync { done, total -> progress += done to total }.size)
            assertTrue(cache.isComplete())
            assertEquals(1, opens)
            assertEquals(0 to 100, progress.first())
            assertEquals(100 to 100, progress.last())
            assertEquals(100, BuiltinEmojiCache.fromTestInput(root) { opens += 1; ByteArrayInputStream(pack) }.sync().size)
            assertEquals(1, opens)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun damagedCacheIsRebuiltFromEmbeddedPack() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-repair-test").toFile()
        val pack = packOf((0 until 100).map { "id$it" to gifBytes() })
        try {
            BuiltinEmojiCache.fromTestInput(root) { ByteArrayInputStream(pack) }.sync()
            java.io.File(root, "builtin/id0.gif").writeText("broken")
            assertFalse(BuiltinEmojiCache.fromTestInput(root) { ByteArrayInputStream(pack) }.isComplete())
            assertEquals(100, BuiltinEmojiCache.fromTestInput(root) { ByteArrayInputStream(pack) }.sync().size)
            assertTrue(BuiltinEmojiCache.fromTestInput(root) { ByteArrayInputStream(pack) }.isComplete())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun invalidGifIsRejected() {
        assertTrue(BuiltinEmojiCache.hasGifHeader(gifBytes()))
        assertFalse(BuiltinEmojiCache.hasGifHeader("RIFF00".toByteArray()))
    }

    private fun packOf(items: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("MCTIER_EMOJI_PACK_V3\u0000".toByteArray(Charsets.US_ASCII))
        body.writeU32(items.size)
        items.forEach { (id, bytes) ->
            val idBytes = id.toByteArray(Charsets.US_ASCII)
            body.writeU16(idBytes.size)
            body.writeU32(bytes.size)
            body.write(idBytes)
            body.write(bytes)
        }
        return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(body.toByteArray()) } }.toByteArray()
    }

    private fun gifBytes(): ByteArray = "GIF89a".toByteArray(Charsets.US_ASCII)

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 255)
        write((value ushr 8) and 255)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        repeat(4) { index -> write((value ushr (index * 8)) and 255) }
    }
}
