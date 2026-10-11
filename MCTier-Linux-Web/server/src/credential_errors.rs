// Explain upstream credential failures without changing keys or weakening storage.
pub fn explain(message: String) -> String {
    match message.as_str() {
        "Chat identity credential is locked or unavailable" | "System credential store is locked or unavailable" =>
            "本机密钥环已锁定或不可用。请解锁登录密钥环，并确认当前桌面会话已运行 Secret Service（如 GNOME Keyring），然后重试。已有聊天身份不会被重置。".into(),
        "Cannot open chat identity credential" =>
            "无法打开本机聊天身份凭据。请确认当前用户的 D-Bus 和 Secret Service 密钥环可用，然后重试。".into(),
        "Cannot save chat identity credential" =>
            "无法保存本机聊天身份凭据。请解锁登录密钥环，确认其可写，然后重试。".into(),
        "Invalid saved chat identity" =>
            "本机已保存的聊天身份凭据无效。请先备份并检查密钥环中的 MCTier/chat-identity-p256-v1 条目；程序不会自动删除或替换它。".into(),
        _ => message,
    }
}

#[cfg(test)]
mod tests {
    use super::explain;

    #[test]
    fn credential_failures_have_actionable_messages_without_resetting_identity() {
        let message = explain("Chat identity credential is locked or unavailable".into());
        assert!(message.contains("解锁登录密钥环"));
        assert!(message.contains("已有聊天身份不会被重置"));
        assert!(explain("Cannot save chat identity credential".into()).contains("可写"));
        assert!(explain("Invalid saved chat identity".into()).contains("不会自动删除或替换"));
    }

    #[test]
    fn unrelated_errors_remain_unchanged() {
        assert_eq!(explain("密码错误".into()), "密码错误");
    }
}
