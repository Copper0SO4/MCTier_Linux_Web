package top.pmh13.mctier.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import kotlinx.coroutines.*
import top.pmh13.mctier.network.QuarkSupport

internal fun createQuarkQr(url: String): Bitmap {
    // Natural module size avoids rounding whitespace from a fixed-size canvas.
    // Retain the standard four-module quiet zone so screenshot scanning works.
    val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
    val side = matrix.width
    return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(side * side) { i -> if (matrix[i % side, i / side]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, side, 0, 0, side, side)
    }
}

@Composable
internal fun QuarkLoginQr(bitmap: Bitmap) {
    Surface(color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(8.dp)) {
        Image(bitmap.asImageBitmap(), L("夸克登录二维码", "Quark sign-in QR"), Modifier.size(184.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
    }
}

@Composable
internal fun QuarkSupportCard() {
    val context = LocalContext.current
    val service = remember { QuarkSupport.get(context) }
    val state by service.view.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var loginMethod by remember { mutableStateOf("qr") }
    var showRules by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(0L) }
    LaunchedEffect(state.expiresIn, state.loginId) {
        val deadline = android.os.SystemClock.elapsedRealtime() + state.expiresIn * 1000
        do {
            remaining = ((deadline - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            delay(1000)
        } while (remaining > 0)
    }
    LaunchedEffect(service) { runCatching { service.action("verify") }.onFailure { error = "暂时无法验证夸克登录，请检查网络后重试" } }
    LaunchedEffect(state.loginId) {
        val id = state.loginId ?: return@LaunchedEffect
        var failures = 0
        try {
            if (state.loginMethod == "mobile") awaitCancellation()
            while (isActive) {
                delay((2500L shl failures.coerceAtMost(4)).coerceAtMost(30000))
                try { service.action("poll", id); failures = 0; error = "" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures++; error = L("无法查询扫码状态，请检查网络后刷新二维码", "Cannot check login. Check your network and refresh.") }
            }
        } finally { withContext(NonCancellable) { runCatching { service.action("cancel", id) } } }
    }
    fun run(action: String) {
        scope.launch {
            busy = true; error = ""
            try {
                service.action(action)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { service.view.value.loginId?.let { runCatching { service.action("cancel", it) } } }
                throw e
            } catch (_: Exception) { error = L("操作未完成，请检查网络、系统凭据存储或在夸克中完成验证后重试", "Operation failed. Check network, secure storage or Quark verification.") }
            finally { busy = false }
        }
    }
    val bitmap by produceState<Bitmap?>(null, state.qrUrl) {
        value = state.qrUrl?.let { url -> withContext(Dispatchers.Default) {
            createQuarkQr(url)
        } }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(GrassGreen.copy(alpha = 0.12f), Panel)), RoundedCornerShape(20.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuarkSupportMark(Modifier.size(30.dp))
            Text(L("MCTier × 夸克网盘", "MCTier × Quark Drive"), fontSize = 11.sp, color = TextPrimary.copy(alpha = 0.6f))
            Text(L("夸克替您，持续赞助", "Keep supporting MCTier through Quark"), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(L("登录一次，之后每天自动转存支持开发。", "Sign in once. Daily saves then run automatically."), color = TextPrimary.copy(alpha = 0.65f), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        QuarkPanel {
            Text(if (state.loggedIn) L("您的支持记录", "Your contribution") else L("您的支持，能带来什么", "What your support makes possible"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (state.loggedIn) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        QuarkCaption(L("累计支持", "Recorded days"))
                        Text("${state.stats.successDays}" + L(" 天", " days"), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        QuarkCaption(L("等价赞助", "Equivalent support"))
                        Text("¥${quarkMoney(state.stats.successDays.toLong() * 47)}", color = AccentText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                QuarkCaption(L("仅统计本机确认成功的转存日期。", "Confirmed save dates on this device only."))
                state.stats.firstDay?.let { first ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { QuarkCaption(L("首次支持", "First save")); Text(first, color = TextPrimary, fontSize = 12.sp) }
                        Column(Modifier.weight(1f)) { QuarkCaption(L("最近支持", "Latest save")); Text(state.stats.lastDay.orEmpty(), color = TextPrimary, fontSize = 12.sp) }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(L("等价赞助", "Equivalent support"), color = AccentText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(L("登录后按累计支持天数计算具体金额", "Sign in to calculate the exact amount from recorded support days"), color = TextPrimary.copy(alpha = 0.6f), fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                QuarkCaption(L("夸克提供推广收入，您无需付款。手机单价仅适用于平台认定的移动渠道，本功能无法核实渠道归属。", "Quark provides referral income; no payment needed. Mobile rates require Quark's channel attribution, which this app cannot verify."))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(L("自愿参与", "Optional"), L("无需付款", "No payment"), L("随时退出", "Leave anytime")).forEach { label ->
                    Surface(Modifier.weight(1f), color = GrassGreen.copy(alpha = 0.09f), shape = RoundedCornerShape(8.dp)) {
                        Text(label, color = AccentText, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp, horizontal = 3.dp))
                    }
                }
            }
        }
        QuarkPanel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.loggedIn) L("已连接夸克", "Quark connected") else L("连接您的夸克账号", "Connect your Quark account"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Surface(color = GrassGreen.copy(alpha = 0.1f), shape = RoundedCornerShape(20.dp)) {
                    Text(if (state.loggedIn) L("已登录", "Signed in") else L("未登录", "Not connected"), color = if (state.loggedIn) AccentText else TextPrimary.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                }
            }
            if (state.loggedIn) {
                Text(state.name.ifBlank { L("夸克用户", "Quark user") }, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                QuarkCaption(L("每日自动转存已开启，无需手动操作。", "Automatic daily saves are on. No manual action needed."))
                TextButton(onClick = { run("logout") }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(L("退出登录 / 更换账号", "Sign out / Switch account"), color = AccentText) }
                QuarkCaption(L("退出会删除本机登录凭据，保留支持记录；已转存文件不会删除。", "Signing out removes local credentials and keeps your records. Saved files remain."))
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("qr" to L("扫码登录", "QR code"), "mobile" to L("手机号登录", "Phone / SMS")).forEach { (method, label) ->
                        FilterChip(
                            selected = loginMethod == method,
                            enabled = !busy,
                            onClick = {
                                if (method != loginMethod) scope.launch {
                                    busy = true; error = ""
                                    try {
                                        state.loginId?.let { service.action("cancel", it) }
                                        loginMethod = method
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { error = L("无法切换登录方式，请重试", "Cannot switch sign-in method. Retry.") }
                                    finally { busy = false }
                                }
                            },
                            label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (loginMethod == "mobile") {
                    QuarkCaption(L("输入手机号和短信验证码即可登录，无需安装夸克 App。", "Sign in with your phone number and SMS code. No Quark app needed."))
                    if (state.loginMethod == "mobile" && state.loginId != null && remaining > 0) {
                        val id = requireNotNull(state.loginId)
                        QuarkMobileLoginForm(id, onTicket = { ticket ->
                            scope.launch {
                                busy = true; error = ""
                                try { service.action("mobile_complete", id, ticket) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = L("登录未完成，请检查网络后重新打开手机号登录。", "Sign-in failed. Check your connection and reopen phone sign-in.") }
                                finally { busy = false }
                            }
                        }, onError = { error = it })
                    } else if (state.loginMethod == "mobile") {
                        QuarkCaption(L("登录页面已过期，请重新打开。", "Sign-in expired. Please reopen the form."))
                    }
                } else {
                    bitmap?.takeIf { remaining > 0 }?.let {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            QuarkLoginQr(it)
                            Text(L("请用夸克扫码 · ${remaining}s 后自动更新", "Scan with Quark · refreshes in ${remaining}s"), color = AccentText, fontSize = 11.sp, textAlign = TextAlign.Center)
                            QuarkCaption(L("同一手机：截屏后在夸克「扫一扫」中选择相册识别，请在有效期内完成。", "On this phone: take a screenshot, then select it from the album in Quark Scan before expiry."))
                        }
                    }
                    if (state.qrUrl != null && remaining == 0L) QuarkCaption(L("二维码已过期，正在尝试更新；断网时请稍后手动刷新。", "QR expired. Refreshing; if offline, retry manually later."))
                }
                Button(onClick = { run(if (loginMethod == "mobile") "mobile_login" else "login") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = OnAccent)) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = OnAccent, strokeWidth = 2.dp)
                    else Text(if (loginMethod == "mobile") {
                        if (state.loginMethod == "mobile") L("重新打开手机号登录", "Reopen phone sign-in") else L("开始手机号登录", "Start phone sign-in")
                    } else if (state.qrUrl == null) L("获取登录二维码", "Get sign-in QR code") else L("刷新二维码", "Refresh QR code"))
                }
            }
            if (state.result.isNotEmpty()) Text(state.result.replace("你", "您"), color = AccentText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(GrassGreen.copy(alpha = 0.07f), RoundedCornerShape(10.dp)).padding(10.dp))
            if (error.isNotEmpty()) Text(error, color = DangerRed, fontSize = 12.sp)
            if (error.isNotEmpty() && !state.loggedIn) TextButton(onClick = { run("logout") }, enabled = !busy) { Text(L("清除本机登录数据", "Clear local login data"), color = AccentText) }
        }
        Column {
            TextButton(onClick = { showRules = !showRules }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                Text(L("收益参考", "Reference rates"), color = TextPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(if (showRules) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (showRules) L("收起", "Collapse") else L("展开", "Expand"), tint = AccentText)
            }
            if (showRules) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = GrassGreen.copy(alpha = 0.06f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, TextPrimary.copy(alpha = 0.1f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuarkRateRow(L("有效转存渠道", "Eligible save channel"), L("开发者参考收益 / 次", "Developer reference income / save"), header = true)
                        HorizontalDivider(color = TextPrimary.copy(alpha = 0.1f))
                        QuarkRateRow(L("手机端", "Mobile"), "¥0.47")
                        HorizontalDivider(color = TextPrimary.copy(alpha = 0.1f))
                        QuarkRateRow(L("电脑端", "Desktop"), "¥0.22")
                    }
                }
            }
        }
        Text(L("自愿参与，不参与也能使用全部功能。", "Participation is optional. All features remain available."), color = TextPrimary.copy(alpha = 0.55f), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
    }
}

@Composable
private fun QuarkPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, TextPrimary.copy(alpha = 0.1f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun QuarkCaption(text: String) {
    Text(text, color = TextPrimary.copy(alpha = 0.65f), fontSize = 11.sp, lineHeight = 17.sp)
}

@Composable
private fun QuarkRateRow(label: String, amount: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = TextPrimary.copy(alpha = 0.7f), fontSize = 12.sp)
        Text(amount, modifier = Modifier.weight(1.3f), textAlign = TextAlign.Center, color = if (header) TextPrimary.copy(alpha = 0.7f) else AccentText, fontSize = 12.sp)
    }
}

@Composable
private fun QuarkSupportMark(modifier: Modifier = Modifier, pulse: Float = 1f) {
    Icon(Icons.Rounded.Favorite, null, modifier.graphicsLayer { scaleX = pulse; scaleY = pulse },
        tint = androidx.compose.ui.graphics.Color(0xFFE14364))
}

@Composable
internal fun QuarkSupportDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.padding(horizontal = 12.dp, vertical = 24.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.94f), color = Panel, shape = RoundedCornerShape(26.dp), border = BorderStroke(1.dp, TextPrimary.copy(alpha = 0.1f))) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L("支持 MCTier", "Support MCTier"), color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, L("关闭赞助窗口", "Close support"), tint = TextPrimary.copy(alpha = 0.65f)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) { QuarkSupportCard() }
            }
        }
    }
}

@Composable
internal fun QuarkSupportEntry(onOpen: () -> Unit) {
    val service = QuarkSupport.get(LocalContext.current)
    val state by service.view.collectAsStateWithLifecycle()
    // Remove the animation from composition as soon as login succeeds.
    val glow = if (!state.loggedIn) {
        val transition = rememberInfiniteTransition(label = "quarkInvitation")
        val value by transition.animateFloat(initialValue = 0.12f, targetValue = 0.32f, animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "quarkGlow")
        value
    } else 0.12f
    OutlinedButton(
        onClick = onOpen, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.heightIn(min = 58.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFDF4867).copy(alpha = glow + 0.25f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFDF4867).copy(alpha = glow), contentColor = MaterialTheme.colorScheme.error),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            QuarkSupportMark(Modifier.size(22.dp), pulse = 1f + (glow - 0.12f) * 0.6f)
        }
    }
}

private fun quarkMoney(cents: Long): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
