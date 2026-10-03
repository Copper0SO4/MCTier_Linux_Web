package top.pmh13.mctier.network

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class QuarkSupportView(
    val ready: Boolean = false,
    val loggedIn: Boolean = false, val name: String = "", val enabled: Boolean = false,
    val dismissed: Boolean = false, val result: String = "", val qrUrl: String? = null,
    val loginId: String? = null, val expiresIn: Long = 0, val loginMethod: String? = null,
    val stats: QuarkSupportStats = QuarkSupportStats(),
)

/** Signing in enables daily automatic saves. Credentials are used only for direct Quark requests. */
class QuarkSupport private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val logoutMarker = File(context.noBackupFilesDir, "quark-logout.requested")
    private val file = AtomicFile(File(context.noBackupFilesDir, "quark-support.bin"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saved = JSONObject()
    private var loaded = false
    private var startup: Job? = null
    @Volatile private var stopRequested = false
    private data class Login(val token: String, val id: String, val started: Long, val method: String = "qr")
    private var login: Login? = null
    private val cookies = mutableListOf<Cookie>()
    private val state = MutableStateFlow(QuarkSupportView())
    val view = state.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { value ->
                    this@QuarkSupport.cookies.removeAll { it.name == value.name && it.domain == value.domain && it.path == value.path }
                    this@QuarkSupport.cookies.add(value)
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
                return cookies.filter { it.matches(url) }
            }
        }).build()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mctier-quark-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mctier-quark-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun load() {
        if (loaded) return
        // openRead also recovers the legacy AtomicFile .bak after an interrupted write.
        // A missing base file alone must not turn a recoverable old login into a new account.
        val bytes = try { file.openRead().use { it.readBytes() } } catch (e: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            null
        }
        if (bytes != null) {
            check(bytes.size in 28..MAX_STATE_BYTES) { "夸克登录数据损坏" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            saved = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            val list = saved.optJSONArray("cookies") ?: JSONArray()
            for (i in 0 until list.length()) {
                val row = list.getJSONObject(i)
                val url = row.optString("url")
                if (url in ORIGINS) Cookie.parse(url.toHttpUrl(), row.getString("cookie"))?.let { cookies.add(it) }
            }
        }
        if (logoutMarker.exists()) {
            saved.remove("account"); saved.remove("name"); saved.remove("cookies")
            cookies.clear()
            stopRequested = true
            persist()
        }
        loaded = true
    }
    private fun persist() {
        val list = JSONArray()
        if (saved.optString("account").isNotEmpty()) {
            // Store absolute cookie expiry, never reset Max-Age on app restart.
            cookies.filter { it.expiresAt > System.currentTimeMillis() }.forEach { cookie ->
                val url = ORIGINS.firstOrNull { cookie.matches(it.toHttpUrl().newBuilder().encodedPath(cookie.path).build()) }
                if (url != null) list.put(JSONObject().put("url", url).put("cookie", cookie.toString()))
            }
        }
        saved.put("cookies", list)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(saved.toString().toByteArray(Charsets.UTF_8))
        check(bytes.size <= MAX_STATE_BYTES) { "本机夸克记录过大，未提交新操作" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun publish() {
        val qr = login // Keep the ID on expiry so a failed refresh can recover after reconnecting.
        state.value = QuarkSupportView(
            ready = loaded,
            loggedIn = saved.optString("account").isNotEmpty(), name = saved.optString("name"),
            enabled = saved.optString("account").isNotEmpty(), dismissed = saved.optBoolean("dismissed"), result = saved.optString("result"),
            qrUrl = qr?.takeIf { it.method == "qr" }?.let { "https://su.quark.cn/4_eMHBJ".toHttpUrl().newBuilder().addQueryParameter("token", it.token).addQueryParameter("client_id", "532").addQueryParameter("ssb", "weblogin").build().toString() },
            loginId = qr?.id, loginMethod = qr?.method, expiresIn = qr?.let { ((if (it.method == "mobile") 600000 else 120000) - (SystemClock.elapsedRealtime() - it.started)).coerceAtLeast(0) / 1000 } ?: 0,
            stats = QuarkContributionLedger.summary(saved, saved.optString("account"), LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()),
        )
    }
    private fun request(url: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject {
        val target = url.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(target).header("User-Agent", UA).header("Referer", "https://pan.quark.cn/")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (url == "https://pan.quark.cn/account/info" && response.code == 401) return JSONObject().put("success", false)
                check(response.isSuccessful) { "夸克请求未完成（HTTP ${response.code}）" }
                JSONObject(response.body?.string() ?: error("响应为空"))
            }
        } catch (_: Exception) { error("夸克请求失败，请检查网络或在官方客户端完成验证") }
    }
    private fun drive(route: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject {
        // This is the web flow; do not impersonate the Quark app or claim mobile attribution.
        val host = if (route.startsWith("share/")) "drive-h" else "drive-pc"
        val data = request("https://$host.quark.cn/1/clouddrive/$route", mapOf("pr" to "ucpro", "fr" to "pc") + params, body)
        check(data.optInt("code", -1) == 0 && data.optInt("status") == 200) {
            "夸克未接受操作（代码 ${data.optInt("code", -1)}），请检查登录、空间或完成官方验证"
        }
        return data
    }
    private fun begin() {
        val data = request("https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin", mapOf("client_id" to "532", "v" to "1.2"))
        check(data.optInt("status") == 2000000) { "夸克登录二维码暂不可用" }
        val token = data.getJSONObject("data").getJSONObject("members").getString("token")
        check(token.isNotEmpty()) { "夸克未返回登录二维码" }
        login = Login(token, UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
    }
    private fun poll(id: String?) {
        val qr = login ?: return
        if (qr.method != "qr" || qr.id != id) return
        if (SystemClock.elapsedRealtime() - qr.started >= 120000) { begin(); return }
        val data = request("https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken", mapOf("client_id" to "532", "v" to "1.2", "token" to qr.token))
        when (data.optInt("status")) {
            50004001 -> return
            50004002 -> { begin(); return }
            2000000 -> Unit
            else -> { login = null; error("扫码验证未完成，请重新登录或使用官方客户端完成验证") }
        }
        val st = data.getJSONObject("data").getJSONObject("members").getString("service_ticket")
        completeLogin(st)
    }
    private fun beginMobile() {
        check(saved.optString("account").isEmpty()) { "请先退出当前夸克账号" }
        login = Login("", UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), "mobile")
    }
    private fun completeMobile(id: String?, ticket: String?) {
        val attempt = login
        check(attempt != null && attempt.method == "mobile" && saved.optString("account").isEmpty() &&
            QuarkMobileLogin.validAttempt(attempt.id, attempt.started, id, SystemClock.elapsedRealtime())) {
            "手机号登录已取消或过期，请重新打开登录页面"
        }
        check(QuarkMobileLogin.validTicket(ticket)) { "夸克登录返回无效，请重新登录" }
        completeLogin(requireNotNull(ticket))
    }
    private fun completeLogin(st: String) {
        val result = request("https://pan.quark.cn/account/info", mapOf("st" to st))
        check(result.optBoolean("success")) { "夸克未确认登录成功" }
        val account = result.getJSONObject("data")
        val uid = accountId(account)
        check(uid.isNotEmpty()) { "无法确认夸克账号，未保存登录信息" }
        saved.put("account", uid).put("name", account.optString("nickname").ifEmpty { account.optString("nick_name", "夸克用户") }.take(64))
            .put("enabled", true).put("result", "登录成功；已开启每日自动转存")
        login = null
        try { persist() } catch (e: Exception) {
            saved.put("account", "").put("name", "").put("cookies", JSONArray()).put("result", "登录凭据保存失败，请重新登录")
            throw e
        }
        check(!logoutMarker.exists() || logoutMarker.delete()) { "无法恢复自动支持，请重试登录" }
        stopRequested = false
        QuarkDailyWork.reconcile(appContext, true)
        scope.launch { action("daily") }
    }
    private fun accountId(account: JSONObject): String = listOf(account.opt("qid"), account.opt("uid"))
        .filterNotNull().filter { it is String || it is Number }.map { it.toString() }.firstOrNull { it.isNotBlank() && it != "0" }
        ?: cookies.firstOrNull { it.name == "__uid" && it.matches(ORIGINS[0].toHttpUrl()) && it.expiresAt > System.currentTimeMillis() }?.value.orEmpty()
    private fun verifySession(): Boolean {
        val account = saved.optString("account")
        if (account.isEmpty()) return false
        val session = request("https://pan.quark.cn/account/info")
        val success = session.opt("success") as? Boolean ?: error("无法确认登录状态，请稍后重试")
        val uid = if (success) accountId(session.optJSONObject("data") ?: JSONObject()).also {
            check(it.isNotBlank()) { "无法确认登录状态，请稍后重试" }
        } else null
        if (uid == account) return true
        // Verification gates transfers; only explicit logout removes saved credentials.
        saved.put("result", "登录已失效或账号发生变化，请退出后重新登录")
        return false
    }
    private suspend fun daily() {
        val account = saved.optString("account")
        if (account.isEmpty() || stopRequested) return
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val attempts = saved.optJSONObject("attempts") ?: JSONObject()
        if (!QuarkDailyAttempt.due(account, day, attempts.optString(account)) ||
            saved.optJSONObject("contributions")?.optJSONObject(account)?.has(day) == true) return
        if (!verifySession()) return
        val outcome = try {
            saveShare(account, day, attempts)
            QuarkContributionLedger.recordSuccess(saved, account, day)
            "$day：转存成功（不代表计佣成功）"
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (runCatching { verifySession() }.getOrNull() == false) return
            val retry = if (attempts.optString(account) == day) "今日不重复提交" else "将自动重试"
            "$day：${e.message ?: "转存失败"}；$retry"
        }
        saved.put("result", outcome)
        try { persist() } catch (_: Exception) { saved.put("result", "$outcome；本机记录保存失败") }
    }
    private suspend fun saveShare(account: String, day: String, attempts: JSONObject) {
        val token = drive("share/sharepage/token", body = JSONObject().put("pwd_id", SHARE).put("passcode", ""))
            .getJSONObject("data").getString("stoken")
        val fids = JSONArray(); val tokens = JSONArray()
        for (page in 1..20) {
            val files = drive("share/sharepage/detail", mapOf("pwd_id" to SHARE, "stoken" to token, "pdir_fid" to "0", "_page" to page.toString(), "_size" to "100", "_fetch_total" to "1"))
                .getJSONObject("data").getJSONArray("list")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                fids.put(f.getString("fid")); tokens.put(f.getString("share_fid_token"))
            }
            if (files.length() < 100) break
            check(page < 20) { "分享内容过多，已停止本次转存" }
        }
        check(fids.length() > 0) { "分享中没有可转存文件" }
        check(!stopRequested) { "已取消转存" }
        check(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString() == day) { "准备期间已跨日，将按新日期自动转存" }
        var result: JSONObject? = null
        QuarkDailyAttempt.run(account, day, attempts.optString(account), reserve = {
            val previous = attempts.opt(account)
            attempts.put(account, day)
            saved.put("attempts", attempts).put("result", "$day：正在自动转存")
            try { persist() } catch (_: Exception) {
                if (previous == null) attempts.remove(account) else attempts.put(account, previous)
                error("无法保存今日执行记录，未提交转存")
            }
            publish()
        }, transfer = {
            currentCoroutineContext().ensureActive()
            check(!stopRequested && !logoutMarker.exists()) { "已取消转存" }
            result = drive("share/sharepage/save", body = JSONObject().put("pwd_id", SHARE).put("stoken", token)
                .put("fid_list", fids).put("fid_token_list", tokens).put("pdir_fid", "0").put("to_pdir_fid", "0").put("scene", "link"))
        })
        val task = result?.optJSONObject("data")?.optString("task_id").orEmpty()
        check(task.isNotEmpty()) { "转存已提交但无任务编号，请在夸克中确认" }
        for (retry in 0 until 15) {
            check(!stopRequested) { "已停止等待，已提交的任务请在夸克中确认" }
            delay(2000)
            val status = drive("task", mapOf("task_id" to task, "retry_index" to retry.toString())).getJSONObject("data").optInt("status")
            if (status == 2) return
            check(status != 3 && status != 4) { "夸克转存任务失败，请检查空间或分享状态" }
        }
        error("转存已提交但完成状态未确认，请在夸克中查看")
    }
    suspend fun action(action: String, loginId: String? = null, serviceTicket: String? = null) = withContext(Dispatchers.IO) {
        if (action == "logout") {
            stopRequested = true
            // Durable cancellation precedes waiting for the in-flight operation's mutex.
            java.io.FileOutputStream(logoutMarker).use { it.write(1); it.fd.sync() }
            QuarkDailyWork.reconcile(appContext, false)
            client.dispatcher.cancelAll()
        }
        mutex.withLock {
            try { load() } catch (e: Exception) {
                if (action != "logout") throw e
                file.delete(); check(!file.baseFile.exists()) { "无法移除本机登录凭据，请重试" }
                saved = JSONObject(); cookies.clear(); loaded = true
            }
            try {
                when (action) {
                    "status" -> QuarkDailyWork.reconcile(appContext, saved.optString("account").isNotEmpty() && !stopRequested)
                    "daily" -> daily()
                    "verify" -> { verifySession(); Unit }
                    "login" -> begin()
                    "poll" -> poll(loginId)
                    "mobile_login" -> beginMobile()
                    "mobile_complete" -> completeMobile(loginId, serviceTicket)
                    "cancel" -> if (login?.id == loginId) login = null
                    "dismiss" -> { saved.put("dismissed", true); persist() }
                    "logout" -> {
                        // A login finishing while logout waited for the mutex may have
                        // registered work again. Make logout the final state transition.
                        stopRequested = true
                        java.io.FileOutputStream(logoutMarker).use { it.write(1); it.fd.sync() }
                        QuarkDailyWork.reconcile(appContext, false)
                        saved = JSONObject().put("attempts", saved.optJSONObject("attempts") ?: JSONObject()).put("dismissed", saved.optBoolean("dismissed"))
                            .put("contributions", saved.optJSONObject("contributions") ?: JSONObject())
                            .put("result", "已退出本设备登录；转存文件和本机统计保留，重登原账号可查看")
                        login = null; cookies.clear()
                        try { persist() } catch (_: Exception) {
                            file.delete(); check(!file.baseFile.exists()) { "无法移除本机登录凭据，请重试" }
                            saved.put("result", "已退出登录；本机存储不可用，统计仅暂存于内存，重启后可能丢失")
                        }
                    }
                    else -> error("未知夸克操作")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (action != "daily") throw e
                saved.put("result", e.message ?: "自动转存暂未完成，将自动重试")
            } finally { publish() }
        }
    }
    /** true means failure before submission: WorkManager may safely back off and retry. */
    suspend fun backgroundDaily(): Boolean {
        action("daily")
        return mutex.withLock {
            val account = saved.optString("account")
            val day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
            !stopRequested && !logoutMarker.exists() && account.isNotEmpty() &&
                saved.optJSONObject("attempts")?.optString(account) != day &&
                saved.optJSONObject("contributions")?.optJSONObject(account)?.has(day) != true
        }
    }
    /** Upgrade restores scheduling from the existing encrypted state, without cloud verification. */
    suspend fun restoreAfterUpdate() = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            publish()
            // Wait for the WorkManager transaction before the broadcast receiver finishes.
            QuarkDailyWork.reconcile(appContext, saved.optString("account").isNotEmpty() && !stopRequested)
                .result.get(5, TimeUnit.SECONDS)
        }
    }
    @Synchronized
    fun onLaunch() {
        // One process-wide loop survives closing the support dialog and activity recreation.
        // Resume also checks immediately after Android has suspended the process.
        if (startup?.isActive == true) {
            scope.launch { runCatching { action("daily") } }
            return
        }
        startup = scope.launch {
            // Publish local login state before any cloud request, so the invitation
            // can render immediately even while offline or a transfer is running.
            runCatching { action("status") }
            while (isActive) {
                runCatching { action("daily") }
                delay(15 * 60_000)
            }
        }
    }
    companion object {
        private const val MAX_STATE_BYTES = 4 * 1024 * 1024
        private const val SHARE = "aee110172d26"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val ORIGINS = listOf("https://pan.quark.cn/", "https://drive-pc.quark.cn/", "https://drive-h.quark.cn/")
        @Volatile private var instance: QuarkSupport? = null
        fun get(context: Context): QuarkSupport = instance ?: synchronized(this) {
            instance ?: QuarkSupport(context.applicationContext).also { instance = it }
        }
    }
}
