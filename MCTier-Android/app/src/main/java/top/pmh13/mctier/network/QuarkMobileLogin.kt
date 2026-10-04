package top.pmh13.mctier.network

/** Official CAS returns a 32-character service ticket, never the user's SMS code. */
internal object QuarkMobileLogin {
    const val ORIGIN = "https://uop.quark.cn"
    const val URL = "$ORIGIN/cas/custom/login?custom_login_type=mobile&client_id=532&display=pc"
    fun validTicket(ticket: String?): Boolean =
        ticket != null && Regex("[A-Za-z0-9]{32}").matches(ticket)
    fun validAttempt(expectedId: String, started: Long, id: String?, now: Long): Boolean =
        expectedId == id && now - started in 0 until 600_000
}
