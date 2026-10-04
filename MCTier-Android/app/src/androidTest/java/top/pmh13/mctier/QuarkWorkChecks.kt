package top.pmh13.mctier

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import top.pmh13.mctier.network.*

/** Isolated encrypted ledger + actual WorkManager database; no real Quark network requests. */
internal class QuarkWorkChecks(private val test: Instrumentation) {
    fun run() {
        val result = Bundle(); var code = 0
        val context = test.targetContext
        val singleton = QuarkSupport::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val original = QuarkSupport.get(context)
        val directory = File(context.cacheDir, "quark-work-check-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = directory }
        val constructor = QuarkSupport::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val manager = WorkManager.getInstance(context)
        try { runBlocking {
            original.action("status")
            check(!original.view.value.loggedIn) { "Test requires no real Quark login; existing credentials were not modified" }
            var service = constructor.newInstance(isolated)
            singleton.set(null, service)
            service.action("status")
            val savedField = QuarkSupport::class.java.getDeclaredField("saved").apply { isAccessible = true }
            val saved = savedField.get(service) as JSONObject
            val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
            saved.put("account", "work-test-account").put("name", "Work test")
                .put("attempts", JSONObject().put("work-test-account", today))
            QuarkContributionLedger.recordSuccess(saved, "work-test-account", today)
            val cookieField = QuarkSupport::class.java.getDeclaredField("cookies").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val cookies = cookieField.get(service) as MutableList<okhttp3.Cookie>
            cookies += okhttp3.Cookie.Builder().name("work-test-cookie").value("synthetic-cookie-only").domain("pan.quark.cn").expiresAt(System.currentTimeMillis() + 86400000).build()
            // Write the pre-WorkManager payload/envelope directly, not with the new writer.
            // Legacy manual-enabled=false accounts still retain their authenticated session.
            val expiry = cookies.single().expiresAt / 1000 * 1000
            val legacy = JSONObject(saved.toString()).put("enabled", false).put("cookies", org.json.JSONArray().put(
                JSONObject().put("url", "https://pan.quark.cn/").put("cookie", cookies.single().toString())))
            val key = QuarkSupport::class.java.getDeclaredMethod("key").apply { isAccessible = true }.invoke(service) as javax.crypto.SecretKey
            val encrypt = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
            val oldBytes = encrypt.iv + encrypt.doFinal(legacy.toString().toByteArray(Charsets.UTF_8))
            File(directory, "quark-support.bin.bak").writeBytes(oldBytes)
            // An interrupted legacy AtomicFile write leaves only .bak; upgrading must recover it.
            service = constructor.newInstance(isolated); singleton.set(null, service)
            service.restoreAfterUpdate()
            check(service.view.value.loggedIn && service.view.value.stats.successDays == 1)
            check(File(directory, "quark-support.bin").readBytes().contentEquals(oldBytes))
            check(!File(directory, "quark-support.bin").readBytes().toString(Charsets.UTF_8).contains("synthetic-cookie-only"))
            service.action("status")
            // Wait for asynchronous registration in the actual persistent WorkManager database.
            var infos = manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get(10, TimeUnit.SECONDS)
            repeat(30) { if (infos.none { !it.state.isFinished }) { Thread.sleep(100); infos = manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get() } }
            check(infos.count { !it.state.isFinished } == 1)
            val workId = infos.first { !it.state.isFinished }.id
            repeat(3) { service.action("status") }
            check(manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().filter { !it.state.isFinished }.single().id == workId)
            service = constructor.newInstance(isolated); singleton.set(null, service)
            service.action("status")
            check(service.view.value.loggedIn && service.view.value.stats.successDays == 1)
            @Suppress("UNCHECKED_CAST") val restored = cookieField.get(service) as MutableList<okhttp3.Cookie>
            check(restored.any { it.name == "work-test-cookie" && it.value == "synthetic-cookie-only" })
            check(restored.single().expiresAt == expiry)
            val worker = TestListenableWorkerBuilder<QuarkDailyWorker>(context).build()
            check(worker.doWork() == ListenableWorker.Result.success())
            check(service.view.value.stats.successDays == 1)
            // Exercise the actual due-transfer flow using an in-process fake Quark server.
            // No cookie or cloud write reaches the Internet.
            val current = savedField.get(service) as JSONObject
            current.getJSONObject("attempts").remove("work-test-account")
            current.getJSONObject("contributions").getJSONObject("work-test-account").remove(today)
            val submitted = java.util.concurrent.atomic.AtomicInteger()
            val offline = java.util.concurrent.atomic.AtomicBoolean(true)
            val clientField = QuarkSupport::class.java.getDeclaredField("client").apply { isAccessible = true }
            val client = clientField.get(service) as okhttp3.OkHttpClient
            clientField.set(service, client.newBuilder().addInterceptor { chain ->
                if (offline.get()) throw java.io.IOException("Synthetic offline")
                val request = chain.request()
                val data = when {
                    request.url.encodedPath == "/account/info" -> """{"success":true,"data":{"qid":"work-test-account"}}"""
                    request.url.encodedPath.endsWith("sharepage/token") -> """{"code":0,"status":200,"data":{"stoken":"test-token"}}"""
                    request.url.encodedPath.endsWith("sharepage/detail") -> """{"code":0,"status":200,"data":{"list":[{"fid":"test-file","share_fid_token":"file-token"}]}}"""
                    request.url.encodedPath.endsWith("sharepage/save") -> {
                        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                        check(JSONObject(body).getString("pwd_id") == "aee110172d26")
                        check(submitted.incrementAndGet() == 1)
                        """{"code":0,"status":200,"data":{"task_id":"test-task"}}"""
                    }
                    request.url.encodedPath.endsWith("/task") -> """{"code":0,"status":200,"data":{"status":2}}"""
                    else -> error("Unexpected Quark request: ${request.url.encodedPath}")
                }
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(data.toResponseBody("application/json".toMediaType())).build()
            }.build())
            check(TestListenableWorkerBuilder<QuarkDailyWorker>(context).build().doWork() == ListenableWorker.Result.retry())
            check(submitted.get() == 0)
            offline.set(false)
            val background = async(Dispatchers.IO) { TestListenableWorkerBuilder<QuarkDailyWorker>(context).build().doWork() }
            val foreground = async(Dispatchers.IO) { service.action("daily") }
            check(background.await() == ListenableWorker.Result.success()); foreground.await()
            check(submitted.get() == 1 && service.view.value.stats.successDays == 1)
            service = constructor.newInstance(isolated); singleton.set(null, service)
            service.action("status")
            check(service.view.value.stats.successDays == 1)
            val beforeVerification = File(directory, "quark-support.bin").readBytes()
            val verificationClient = clientField.get(service) as okhttp3.OkHttpClient
            for (response in listOf("""{"success":false}""", """{"success":true,"data":{"qid":"other-account"}}""")) {
                clientField.set(service, verificationClient.newBuilder().addInterceptor { chain ->
                    check(chain.request().url.encodedPath == "/account/info")
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                        .body(response.toResponseBody("application/json".toMediaType())).build()
                }.build())
                service.action("verify")
                check(service.view.value.loggedIn && service.view.value.stats.successDays == 1)
                check(File(directory, "quark-support.bin").readBytes().contentEquals(beforeVerification))
                check(manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().filter { !it.state.isFinished }.single().id == workId)
            }
            service.action("logout")
            repeat(50) {
                if (manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().any { !it.state.isFinished }) Thread.sleep(100)
            }
            check(manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().all { it.state.isFinished })
            service = constructor.newInstance(isolated); singleton.set(null, service)
            service.restoreAfterUpdate()
            check(manager.getWorkInfosForUniqueWork(QuarkDailyWork.NAME).get().all { it.state.isFinished })
            check(TestListenableWorkerBuilder<QuarkDailyWorker>(context).build().doWork() == ListenableWorker.Result.success())
            check(!service.view.value.loggedIn)
            @Suppress("UNCHECKED_CAST") val cleared = cookieField.get(service) as MutableList<okhttp3.Cookie>
            check(cleared.isEmpty())
            result.putString("stream", "PASS: legacy encrypted state and interrupted-write backup upgrade; cookie expiry preserved; upgrade registers unique persistent work; credential/ledger reload; offline retry; concurrent foreground/worker submits once; logout cancels work and upgrade keeps logout. Fake Quark responses; no real cloud transfer.\n")
        } } catch (e: Throwable) { code = 1; result.putString("stream", "FAIL: ${e.stackTraceToString()}") }
        finally {
            singleton.set(null, original)
            runBlocking { runCatching { original.action("status") } }
            directory.deleteRecursively()
        }
        test.finish(code, result)
    }
}
