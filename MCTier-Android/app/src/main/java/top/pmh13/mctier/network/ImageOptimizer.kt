package top.pmh13.mctier.network

import android.util.Log
import java.io.File
import top.pmh13.mctier.data.sniffChatImageMime

/** Shared Rust lossless codecs, always called from the repository IO scope. */
object ImageOptimizer {
    const val MaxSourceBytes = 64 * 1024 * 1024
    private val available = try {
        System.loadLibrary("mctier_image_optimizer")
        true
    } catch (error: LinkageError) {
        Log.w("ImageOptimizer", "Image optimizer unavailable; retaining original images", error)
        false
    }

    @JvmStatic private external fun optimizeNative(bytes: ByteArray): ByteArray?

    fun optimize(bytes: ByteArray): ByteArray {
        if (!available || bytes.isEmpty() || bytes.size > MaxSourceBytes || sniffChatImageMime(bytes) == null) return bytes
        return runCatching { optimizeNative(bytes) }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size < bytes.size && sniffChatImageMime(it) != null } ?: bytes
    }

    /** Optimizes only the app's outgoing cache copy, never the user's source. */
    fun optimizeFile(file: File): String? {
        if (file.length() !in 1..MaxSourceBytes.toLong()) return null
        val header = ByteArray(12)
        file.inputStream().use { it.read(header) }
        val originalMime = sniffChatImageMime(header) ?: return null
        val original = file.readBytes()
        val result = optimize(original)
        if (result.size < original.size) file.writeBytes(result)
        return sniffChatImageMime(result) ?: originalMime
    }
}
