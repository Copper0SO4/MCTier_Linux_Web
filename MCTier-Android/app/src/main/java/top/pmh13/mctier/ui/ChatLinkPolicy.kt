package top.pmh13.mctier.ui

/** Accept only ordinary HTTP(S) URLs without embedded credentials or controls. */
internal fun isSafeChatUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank() && uri.userInfo == null && !value.any(Char::isISOControl)
}.getOrDefault(false)
