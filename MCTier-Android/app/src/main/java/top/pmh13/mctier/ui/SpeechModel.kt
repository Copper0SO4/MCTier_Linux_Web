package top.pmh13.mctier.ui

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal object SpeechModel {
    fun prepare(context: Context, progress: (Long, Long) -> Unit): File {
        val manifest = JSONObject(context.assets.open("speech-model.json").bufferedReader().use { it.readText() })
        return prepareBundled(manifest, context.noBackupFilesDir, { context.assets.open(it) }, progress)
    }

    // Asset access is injected so extraction and corruption recovery can be tested offline on the JVM.
    internal fun prepareBundled(manifest: JSONObject, root: File, openAsset: (String) -> InputStream, progress: (Long, Long) -> Unit): File {
        val modelId = manifest.getString("id")
        check(modelId.matches(Regex("[a-zA-Z0-9_-]+")))
        val directory = File(root, "speech-models/$modelId").apply { mkdirs() }
        val files = manifest.getJSONArray("files")
        val total = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("size") }
        var completed = 0L
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index)
            val size = entry.getLong("size")
            val hash = entry.getString("sha256")
            val name = entry.getString("name")
            val compression = entry.optString("compression")
            check(compression in setOf("", "gzip"))
            check(name in setOf("model.int8.onnx", "tokens.txt") && size > 0 && hash.matches(Regex("[a-f0-9]{64}")))
            val destination = File(directory, name)
            if (!verified(destination, size, hash)) {
                val pending = File(directory, "${destination.name}.partial")
                try {
                    val assetName = "speech-model/${destination.name}" + if (compression == "gzip") ".gzip" else ""
                    openAsset(assetName).use { asset ->
                        (if (compression == "gzip") GZIPInputStream(asset) else asset).use { input -> pending.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var received = 0L
                        var lastPercent = -1L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            check(received <= size) { "离线语音模型大小不匹配" }
                            output.write(buffer, 0, count)
                            val percent = (completed + received) * 100 / total
                            if (percent != lastPercent) { progress(completed + received, total); lastPercent = percent }
                        }
                        output.fd.sync()
                    } } }
                    check(verified(pending, size, hash)) { "内置语音模型校验失败，请重新安装 MCTier" }
                    check(!destination.exists() || destination.delete())
                    check(pending.renameTo(destination)) { "无法保存离线语音模型" }
                } finally { pending.delete() }
            }
            completed += size
            progress(completed, total)
        }
        return directory
    }

    private fun verified(file: File, size: Long, expected: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        return runCatching {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            hash.digest().joinToString("") { "%02x".format(it) } == expected
        }.getOrDefault(false)
    }
}
