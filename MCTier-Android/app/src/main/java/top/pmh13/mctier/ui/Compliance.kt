package top.pmh13.mctier.ui

import android.content.Context

/**
 * 合规相关：首次启动同意状态存储 + 隐私政策/用户协议/权限用途说明文案。
 * 说明：这些文本为产品内可见的合规告知内容，作为源码内置，便于在首启弹窗与设置中随时查看。
 * 本文档为模板性质，正式上线前请结合实际运营主体、联系方式与业务由法律专业人士最终审定。
 */
object ConsentStore {
    private const val PREF = "mctier_compliance"
    private const val KEY_AGREED = "agreed_v1"

    fun isAgreed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AGREED, false)

    fun setAgreed(ctx: Context, agreed: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_AGREED, agreed).apply()
    }
}

/** 高风险功能的一次性同意：每个功能首次使用前需单独同意，之后不再重复弹出 */
object FeatureConsent {
    private const val PREF = "mctier_feature_consent"
    fun isAgreed(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(key, false)
    fun setAgreed(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(key, true).apply()
    }
}

object ComplianceTexts {
    @androidx.compose.runtime.Composable
    private fun document(id: String): String {
        val context = androidx.compose.ui.platform.LocalContext.current
        val docs = androidx.compose.runtime.remember(context) {
            org.json.JSONObject(context.assets.open("compliance.json").bufferedReader().use { it.readText() }).getJSONArray("documents")
        }
        val doc = (0 until docs.length()).map { docs.getJSONObject(it) }.first { it.getString("id") == id }
        return L(doc.getString("zh"), doc.getString("en"))
    }
    @androidx.compose.runtime.Composable
    fun privacyPolicy() = document("privacy")
    @androidx.compose.runtime.Composable
    fun userAgreement() = document("terms")
    @androidx.compose.runtime.Composable
    fun permissionUsage() = document("permissions")
    @androidx.compose.runtime.Composable
    fun disclaimer() = document("disclaimer")

    /** 高风险功能使用前的一次性同意提示文案。kind: remote/screen/voice/folder */
    fun featureConsent(kind: String): String = when (kind) {
        "remote" -> L(
            "使用「远程控制对方设备」前请确认：\n\n· 仅可控制你本人设备，或已获对方真实、自愿明确授权的设备；\n· 严禁用于偷窥、窃取信息、非法控制他人设备等行为，否则可能触犯《刑法》及《网络安全法》，由你自行承担法律责任；\n· 对方可随时终止控制。\n\n点击「同意并继续」表示你已阅读《用户协议》与《免责声明》并承诺合法使用。",
            "Before using \"Remote control\":\n\n- Only control your own devices or devices you are genuinely, voluntarily and explicitly authorized to control;\n- Spying, stealing information or unauthorized control is strictly forbidden and may violate the law; you bear all liability;\n- The other party can stop the session anytime.\n\nClicking \"Agree & Continue\" means you have read the User Agreement and Disclaimer and pledge lawful use.",
        )
        "screen" -> L(
            "使用「屏幕共享」前请确认：\n\n· 共享屏幕时对方将看到你当前屏幕的全部内容，请避免展示银行、验证码、隐私等敏感信息；\n· 不得共享含违法、侵权或他人隐私的画面；\n· 责任由你自行承担。\n\n点击「同意并继续」表示你已阅读《用户协议》与《免责声明》并承诺合法使用。",
            "Before using \"Screen sharing\":\n\n- Others will see everything on your current screen; avoid showing bank info, verification codes or private data;\n- Do not share illegal, infringing or others' private content;\n- You bear all responsibility.\n\nClicking \"Agree & Continue\" means you have read the User Agreement and Disclaimer and pledge lawful use.",
        )
        "voice" -> L(
            "启用「变声器」前请确认：\n\n· 变声功能仅供娱乐与正常社交；\n· 严禁用于电信网络诈骗、冒充他人身份或任何欺骗、骚扰行为，违者依法自负责任。\n\n点击「同意并继续」表示你已阅读并承诺合法使用。",
            "Before enabling the \"Voice changer\":\n\n- It is for entertainment and normal social use only;\n- Using it for telecom fraud, impersonation, deception or harassment is strictly forbidden; violators bear legal liability.\n\nClicking \"Agree & Continue\" means you have read and pledge lawful use.",
        )
        "folder" -> L(
            "使用「文件夹共享」前请确认：\n\n· 不得共享含违法、淫秽、侵权或他人隐私的文件；\n· 共享内容及由此产生的责任由你自行承担。\n\n点击「同意并继续」表示你已阅读《用户协议》与《免责声明》并承诺合法使用。",
            "Before using \"Folder sharing\":\n\n- Do not share illegal, obscene, infringing or others' private files;\n- You bear all responsibility for shared content.\n\nClicking \"Agree & Continue\" means you have read the User Agreement and Disclaimer and pledge lawful use.",
        )
        else -> ""
    }
}
