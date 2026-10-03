package top.pmh13.mctier

import android.app.Instrumentation
import android.os.Bundle
import android.util.AtomicFile
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import top.pmh13.mctier.network.QuarkDailyWork
import top.pmh13.mctier.network.QuarkSupport

/** Two invocations separated by adb install -r. Uses only synthetic credentials. */
internal class QuarkUpgradeChecks(private val test: Instrumentation) {
    private val context = test.targetContext
    private val checkpoint = File(context.filesDir, "quark-upgrade-check")
    private val stateFile = AtomicFile(File(context.noBackupFilesDir, "quark-support.bin"))
    private val marker = File(context.noBackupFilesDir, "quark-logout.requested")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun run(mode: String) {
        val result = Bundle(); var code = 0
        try { runBlocking {
            when (mode) {
                "seed" -> {
                    check(!checkpoint.exists()) { "Previous upgrade test checkpoint must be restored first" }
                    val service = QuarkSupport.get(context)
                    service.action("status")
                    check(!service.view.value.loggedIn) { "Existing Quark login was not modified" }
                    check(checkpoint.mkdirs())
                    if (stateFile.baseFile.exists()) stateFile.baseFile.copyTo(File(checkpoint, "original.bin"))
                    if (marker.exists()) marker.copyTo(File(checkpoint, "original.stop"))
                    val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
                    val yesterday = LocalDate.parse(today).minusDays(1).toString()
                    val cookie = Cookie.Builder().name("__uid").value("upgrade-test-account").domain("quark.cn")
                        .path("/").secure().expiresAt(System.currentTimeMillis() + 86400000).build()
                    val legacy = JSONObject().put("account", "upgrade-test-account").put("name", "Synthetic upgrade check")
                        .put("enabled", false).put("dismissed", true).put("result", "legacy")
                        .put("attempts", JSONObject().put("upgrade-test-account", today))
                        .put("contributions", JSONObject().put("upgrade-test-account", JSONObject().apply {
                            for (day in listOf(yesterday, today)) put(day, JSONObject().put("pcCents", 22).put("mobileCents", 47).put("rulesVersion", "2026-09-01"))
                        }))
                        .put("cookies", JSONArray().put(JSONObject().put("url", "https://pan.quark.cn/").put("cookie", cookie.toString())))
                    val key = QuarkSupport::class.java.getDeclaredMethod("key").apply { isAccessible = true }.invoke(service) as SecretKey
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                    val bytes = cipher.iv + cipher.doFinal(legacy.toString().toByteArray(Charsets.UTF_8))
                    File(checkpoint, "expected.json").writeText(JSONObject().put("day", today).put("hash", digest(bytes)).put("expiry", cookie.expiresAt / 1000 * 1000).toString())
                    WorkManager.getInstance(context).cancelUniqueWork(QuarkDailyWork.NAME).result.get(10, TimeUnit.SECONDS)
                    val output = stateFile.startWrite()
                    try { output.write(bytes); stateFile.finishWrite(output) } catch (e: Exception) { stateFile.failWrite(output); throw e }
                    check(!marker.exists() || marker.delete())
                    check(WorkManager.getInstance(context).getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().none { !it.state.isFinished })
                    result.putString("stream", "PASS: legacy-format encrypted login and two support days seeded; no periodic task exists. Ready for in-place APK replacement.\n")
                }
                "verify" -> {
                    val expected = JSONObject(File(checkpoint, "expected.json").readText())
                    check(expected.getString("day") == LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) { "Upgrade fixture must be checked on the same day" }
                    val manager = WorkManager.getInstance(context)
                    var active = manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().filter { !it.state.isFinished }
                    repeat(50) { if (active.isEmpty()) { Thread.sleep(100); active = manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().filter { !it.state.isFinished } } }
                    // Check BEFORE status/onLaunch, otherwise the test could register the job itself.
                    check(active.size == 1) { "Package replacement did not register exactly one background task" }
                    check(digest(stateFile.openRead().use { it.readBytes() }) == expected.getString("hash"))
                    val service = QuarkSupport.get(context)
                    service.action("status")
                    check(service.view.value.loggedIn && service.view.value.name == "Synthetic upgrade check")
                    check(service.view.value.stats.successDays == 2 && service.view.value.stats.mobileReferenceCents == 94L)
                    val client = QuarkSupport::class.java.getDeclaredField("client").apply { isAccessible = true }.get(service) as okhttp3.OkHttpClient
                    val cookies = client.cookieJar.loadForRequest("https://pan.quark.cn/".toHttpUrl())
                    check(cookies.single().value == "upgrade-test-account" && cookies.single().expiresAt == expected.getLong("expiry"))
                    check(!service.backgroundDaily()) // Already submitted today; never call real Quark.
                    check(manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().filter { !it.state.isFinished }.single().id == active.single().id)
                    result.putString("stream", "PASS: actual APK replacement retained Keystore-decryptable login, absolute cookie expiry and two support days; update receiver registered one task before UI launch; worker reused the session and ledger without duplicate transfer.\n")
                }
                "cleanup" -> result.putString("stream", "PASS: restored upgrade test checkpoint.\n")
                else -> error("Unknown upgrade check")
            }
        } } catch (e: Throwable) { code = 1; result.putString("stream", "FAIL: ${e.stackTraceToString()}") }
        finally {
            if (mode == "verify" || mode == "cleanup" || code != 0) {
                try { restore() } catch (e: Throwable) { code = 1; result.putString("stream", "${result.getString("stream")}\nRestore failed: ${e.message}") }
            }
        }
        test.finish(code, result)
    }

    private fun restore() = runBlocking {
        if (!checkpoint.exists()) return@runBlocking
        WorkManager.getInstance(context).cancelUniqueWork(QuarkDailyWork.NAME).result.get(10, TimeUnit.SECONDS)
        val original = File(checkpoint, "original.bin")
        if (original.exists()) {
            val output = stateFile.startWrite()
            try { output.write(original.readBytes()); stateFile.finishWrite(output) } catch (e: Exception) { stateFile.failWrite(output); throw e }
        } else stateFile.delete()
        if (File(checkpoint, "original.stop").exists()) File(checkpoint, "original.stop").copyTo(marker, overwrite = true)
        else check(!marker.exists() || marker.delete())
        QuarkSupport::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        QuarkSupport.get(context).action("status")
        check(checkpoint.deleteRecursively())
    }
}
