package top.pmh13.mctier.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class SpeechModelTest {
    private val bytes = "bundled-offline-test-model".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun manifest() = JSONObject("""{"id":"fixture","files":[{"name":"model.int8.onnx","size":${bytes.size},"sha256":"$hash"}]}""")

    @Test fun extractsFromAssetsReusesAndRepairsWithoutNetwork() {
        val root = Files.createTempDirectory("mctier-speech-").toFile()
        try {
            var opened = 0
            var completed = 0L
            val extract = {
                SpeechModel.prepareBundled(manifest(), root, { path ->
                    assertEquals("speech-model/model.int8.onnx", path)
                    opened++
                    bytes.inputStream()
                }, { count, total -> assertTrue(count <= total); completed = count })
            }
            val directory = extract()
            val model = File(directory, "model.int8.onnx")
            assertArrayEquals(bytes, model.readBytes())
            assertEquals(bytes.size.toLong(), completed)
            extract()
            assertEquals(1, opened)
            model.writeBytes(ByteArray(bytes.size))
            extract()
            assertEquals(2, opened)
            assertArrayEquals(bytes, model.readBytes())
            assertFalse(File(directory, "model.int8.onnx.partial").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidBundledBytesFailAndLeaveNoPartialModel() {
        val root = Files.createTempDirectory("mctier-speech-bad-").toFile()
        try {
            val result = runCatching { SpeechModel.prepareBundled(manifest(), root, { ByteArray(bytes.size).inputStream() }, { _, _ -> }) }
            assertTrue(result.isFailure)
            assertFalse(File(root, "speech-models/fixture/model.int8.onnx").exists())
            assertFalse(File(root, "speech-models/fixture/model.int8.onnx.partial").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun gzipExtractionRepairsCacheAndRejectsTruncatedOrOversizedData() {
        val root = Files.createTempDirectory("mctier-speech-gzip-").toFile()
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
        val config = manifest().also { it.getJSONArray("files").getJSONObject(0).put("compression", "gzip") }
        try {
            var opens = 0
            val extract = {
                SpeechModel.prepareBundled(config, root, { name ->
                    assertEquals("speech-model/model.int8.onnx.gzip", name)
                    opens++
                    compressed.inputStream()
                }, { done, total -> assertTrue(done <= total) })
            }
            val directory = extract()
            extract()
            assertEquals(1, opens)
            val model = File(directory, "model.int8.onnx")
            model.writeText("broken")
            extract()
            assertArrayEquals(bytes, model.readBytes())
            model.delete()
            assertTrue(runCatching { SpeechModel.prepareBundled(config, root, { compressed.copyOf(compressed.size - 4).inputStream() }, { _, _ -> }) }.isFailure)
            config.getJSONArray("files").getJSONObject(0).put("size", 1)
            assertTrue(runCatching { extract() }.isFailure)
            assertFalse(model.exists())
            assertFalse(File(directory, "model.int8.onnx.partial").exists())
        } finally { root.deleteRecursively() }
    }
}
