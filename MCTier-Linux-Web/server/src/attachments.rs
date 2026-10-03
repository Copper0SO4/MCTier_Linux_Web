//! Browser downloads use the same signed, encrypted overlay endpoint as desktop.
//! No caller-supplied URL, IP or filesystem path is accepted.
use super::*;
use modules::chat_service::{
    valid_attachment_meta, ChatAttachmentMeta, CHAT_TOKEN_HEADER, MAX_CHAT_ATTACHMENT_BYTES,
};
static DOWNLOADS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(2);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Download {
    owner_player_id: String,
    attachment: ChatAttachmentMeta,
}

pub async fn download(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Json(args): Json<Download>,
) -> Result<Response, ApiError> {
    owner(&app, &headers).await?;
    let _permit = DOWNLOADS.try_acquire().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "已有两个附件正在下载，请稍后再试".into(),
        )
    })?;
    if !valid_attachment_meta(&args.attachment) {
        return Err("文件附件元数据无效".to_string().into());
    }
    let chat = app.chat.lock().await;
    let local = chat
        .get_local_identity()
        .ok_or("聊天会话尚未初始化".to_string())?;
    let token = chat
        .get_chat_token()
        .ok_or("聊天令牌尚未就绪".to_string())?;
    let bytes = if local.player_id == args.owner_player_id {
        let path = chat
            .local_attachment_path(&args.attachment)
            .ok_or("本地附件已失效".to_string())?;
        drop(chat);
        let metadata = tokio::fs::metadata(&path)
            .await
            .map_err(|_| "本地附件不可用".to_string())?;
        if !metadata.is_file() || metadata.len() != args.attachment.size {
            return Err("附件实际大小与消息元数据不一致".to_string().into());
        }
        use tokio::io::AsyncReadExt;
        let file = tokio::fs::File::open(path)
            .await
            .map_err(|_| "读取本地附件失败".to_string())?;
        let mut bytes = Vec::new();
        file.take(args.attachment.size + 1)
            .read_to_end(&mut bytes)
            .await
            .map_err(|_| "读取本地附件失败".to_string())?;
        bytes
    } else {
        let peer = chat
            .peer_by_player_id(&args.owner_player_id)
            .ok_or("附件发送者已离开大厅".to_string())?;
        let path = format!("/api/chat/attachment/{}", args.attachment.id);
        let signed = chat
            .sign_request("GET", &path, &peer.virtual_ip, &[])
            .ok_or("聊天签名不可用".to_string())?;
        drop(chat);
        let host = chat_transport::chat_http_host(&peer.virtual_ip)?;
        let response = chat_transport::with_chat_signature(
            reqwest::Client::builder()
                .timeout(Duration::from_secs(60))
                .connect_timeout(Duration::from_secs(4))
                .redirect(reqwest::redirect::Policy::none())
                .build()
                .map_err(|_| "创建附件客户端失败".to_string())?
                .get(format!("http://{host}:14540{path}"))
                .header(CHAT_TOKEN_HEADER, token.clone()),
            &signed,
        )
        .send()
        .await
        .map_err(|_| "获取聊天附件失败，请检查虚拟网络与对端聊天服务".to_string())?;
        if !response.status().is_success() {
            return Err(format!("获取聊天附件失败: HTTP {}", response.status()).into());
        }
        // Matches the original desktop encrypted response budget.
        let encrypted =
            chat_transport::read_remote_body_limited(response, 90 * 1024 * 1024).await?;
        let chat = app.chat.lock().await;
        if chat.get_chat_token().as_deref() != Some(token.as_str())
            || chat.peer_by_player_id(&peer.player_id).is_none()
        {
            return Err("下载期间大厅会话已变化，请重新下载".to_string().into());
        }
        chat.decrypt_from_peer(&peer, &path, &encrypted)?
    };
    if bytes.len() as u64 != args.attachment.size || bytes.len() as u64 > MAX_CHAT_ATTACHMENT_BYTES
    {
        return Err("附件实际大小与消息元数据不一致".to_string().into());
    }
    // Always download as binary. HTML/SVG attachments cannot execute in our origin.
    Ok((
        [
            (header::CONTENT_TYPE, "application/octet-stream"),
            (header::CONTENT_DISPOSITION, "attachment"),
        ],
        bytes,
    )
        .into_response())
}
