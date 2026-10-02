package top.pmh13.mctier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.pmh13.mctier.network.QuarkSupport

private val startupHighlight = SpanStyle(
    color = Color(0xFF1F7A36),
    fontWeight = FontWeight.Bold,
    background = Color(0x1F27823B),
)

private fun startupParagraph(index: Int) = buildAnnotatedString {
    append("\u3000\u3000")
    if (appLang == "en") {
        when (index) {
            0 -> { append("Please sign in to "); withStyle(startupHighlight) { append("Quark") }; append(" through MCTier. Daily saves can bring in "); withStyle(startupHighlight) { append("a few tenths of a yuan") }; append(" to help fund ongoing updates.") }
            1 -> { append("You can keep supporting MCTier development through Quark Drive "); withStyle(startupHighlight) { append("without spending anything") }; append(". Quark covers the costs.") }
            2 -> { append("Your "); withStyle(startupHighlight) { append("Quark account") }; append(" credentials are stored locally on your phone, and you can sign out at any time.") }
            else -> { append("We hope you will support MCTier. "); withStyle(startupHighlight) { append("Your support is vital to its continued development") }; append("!") }
        }
    } else {
        when (index) {
            0 -> { append("希望您能在MCTier上登录一下"); withStyle(startupHighlight) { append("夸克账号") }; append("，这样就能每天为MCTier带来"); withStyle(startupHighlight) { append("几角钱的转存费") }; append("以维持软件的更新迭代。") }
            1 -> { withStyle(startupHighlight) { append("您不用掏一分钱") }; append("，就能"); withStyle(startupHighlight) { append("利用夸克网盘来持续赞助MCTier") }; append("的开发工作，所有的费用都由夸克出。") }
            2 -> { append("登上的"); withStyle(startupHighlight) { append("夸克账号") }; append("凭证完全保存在您手机本地，并且可以随时退出登录。") }
            else -> { append("希望您能支持一下，"); withStyle(startupHighlight) { append("这对MCTier的发展至关重要") }; append("！") }
        }
    }
}

@Composable
internal fun QuarkStartupPrompt(blocked: Boolean, onSupport: () -> Unit) {
    val context = LocalContext.current
    val service = remember { QuarkSupport.get(context) }
    val state by service.view.collectAsStateWithLifecycle()
    // Activity recreation must not repeat an invitation already dismissed this launch.
    var handled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(service) {
        if (!service.view.value.ready) runCatching { service.action("status") }
    }
    LaunchedEffect(state.ready, state.loggedIn) {
        if (state.ready && state.loggedIn) handled = true
    }
    if (blocked || !state.ready || state.loggedIn || handled) return
    AlertDialog(
        onDismissRequest = { handled = true },
        title = { Text(L("免费持续支持 MCTier", "Support MCTier for free")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(startupParagraph(0))
                Text(startupParagraph(1))
                Text(startupParagraph(2))
                Text(startupParagraph(3))
            }
        },
        dismissButton = {
            Button(onClick = { handled = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEDEDED), contentColor = Color(0xFF686868))) {
                Text(L("下次一定", "Maybe next time"))
            }
        },
        confirmButton = {
            Button(onClick = { handled = true; onSupport() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27823B), contentColor = Color.White)) {
                Text(L("支持一下", "Support MCTier"))
            }
        },
    )
}
