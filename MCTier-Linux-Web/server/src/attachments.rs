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
    let mut changed = app.chat_generation.subscribe();
    let generation = *changed.borrow();
    let transfer = async {
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
            crate::overlay_http::client(Duration::from_secs(60), Duration::from_secs(4))
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
            || chat.peer_by_player_id(&peer.player_id).as_ref() != Some(&peer)
        {
            return Err("下载期间大厅会话已变化，请重新下载".to_string().into());
        }
        chat.decrypt_from_peer(&peer, &path, &encrypted)?
    };
    if bytes.len() as u64 != args.attachment.size || bytes.len() as u64 > MAX_CHAT_ATTACHMENT_BYTES
    {
        return Err("附件实际大小与消息元数据不一致".to_string().into());
    }
    if *app.chat_generation.borrow() != generation {
        return Err("下载期间大厅会话已变化，请重新下载".to_string().into());
    }
    Ok::<_, ApiError>(bytes)
    };
    let bytes = tokio::select! {
        result = transfer => result?,
        _ = changed.changed() => return Err("下载期间大厅会话已变化，请重新下载".to_string().into()),
    };
    Ok(crate::binary_response::download(bytes, _permit))
}

// Bytes selected by the user only. Never accept a source filesystem path.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Upload {
    name: String,
    mime: String,
    recipient_id: Option<String>,
}
static UPLOADS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(2);

pub async fn upload(
    State(app): State<Arc<App>>,
    axum::extract::Query(args): axum::extract::Query<Upload>,
    request: Request,
) -> Result<Json<ChatAttachmentMeta>, ApiError> {
    owner(&app, request.headers()).await?;
    let _permit = UPLOADS.try_acquire().map_err(|_| ApiError(StatusCode::TOO_MANY_REQUESTS, "附件上传繁忙".into()))?;
    let mut changed = app.chat_generation.subscribe();
    let (token, generation) = { let chat = app.chat.lock().await; (chat.get_chat_token().ok_or("聊天会话尚未就绪".to_string())?, *app.chat_generation.borrow()) };
    let mut meta = ChatAttachmentMeta { id: uuid::Uuid::new_v4().to_string(), name: args.name, mime: args.mime, size: 1 };
    if !valid_attachment_meta(&meta) { return Err("附件名称或类型无效".to_string().into()); }
    if *changed.borrow() != generation { return Err("上传期间会话已变化".to_string().into()); }
    let bytes = tokio::select! {
        result = tokio::time::timeout(Duration::from_secs(120), axum::body::to_bytes(request.into_body(), MAX_CHAT_ATTACHMENT_BYTES as usize)) =>
            result.map_err(|_| "附件上传超时".to_string())?.map_err(|_| "附件超过 64 MiB 或上传中断".to_string())?,
        _ = changed.changed() => return Err("上传期间会话已变化".to_string().into()),
    };
    meta.size = bytes.len() as u64;
    if !valid_attachment_meta(&meta) { return Err("附件为空或超过限制".to_string().into()); }
    let chat = app.chat.lock().await;
    if chat.get_chat_token().as_deref() != Some(token.as_str()) || *app.chat_generation.borrow() != generation { return Err("上传期间会话已变化".to_string().into()); }
    if args.recipient_id.as_deref().is_some_and(|id| chat.peer_by_player_id(id).is_none()) { return Err("私聊收件人已离线".to_string().into()); }
    let mut budget = app.upload_budget.lock().await;
    if budget.1 >= 128 || budget.0 + meta.size > 256 * 1024 * 1024 { return Err("本次会话附件达到 128 个或 256 MiB，请退出后重新加入".to_string().into()); }
    let path = std::env::temp_dir().join(format!("mctier-chat-{}", meta.id));
    let mut options = tokio::fs::OpenOptions::new();
    options.write(true).create_new(true).mode(0o600);
    struct PendingFile(Option<std::path::PathBuf>);
    impl Drop for PendingFile { fn drop(&mut self) { if let Some(path) = &self.0 { let _ = std::fs::remove_file(path); } } }
    let mut file = options.open(&path).await.map_err(|_| "无法创建附件缓存".to_string())?;
    let mut pending = PendingFile(Some(path.clone()));
    use tokio::io::AsyncWriteExt;
    let result = async {
        file.write_all(&bytes).await.map_err(|_| "附件保存失败".to_string())?;
        file.flush().await.map_err(|_| "附件保存失败".to_string())?;
        chat.register_attachment(meta.clone(), path.clone(), args.recipient_id)
    }.await;
    if let Err(error) = result { let _ = tokio::fs::remove_file(&path).await; return Err(error.into()); }
    pending.0 = None;
    budget.0 += meta.size;
    budget.1 += 1;
    Ok(Json(meta))
}

pub fn prepare_image(data: &str) -> Result<Value, ApiError> {
    use base64::{engine::general_purpose::STANDARD, Engine};
    if data.len() > 2 * 1024 * 1024 * 4 / 3 + 4 { return Err("内嵌图片超过 2 MiB".to_string().into()); }
    let bytes = STANDARD.decode(data).map_err(|_| "图片编码无效".to_string())?;
    if bytes.is_empty() || bytes.len() > 2 * 1024 * 1024 { return Err("图片大小无效".to_string().into()); }
    let mime = if bytes.starts_with(b"\x89PNG\r\n\x1a\n") { "image/png" }
        else if bytes.starts_with(&[0xff, 0xd8, 0xff]) { "image/jpeg" }
        else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") { "image/gif" }
        else if bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WEBP") { "image/webp" }
        else { return Err("仅支持 PNG、JPEG、GIF、WebP".to_string().into()); };
    Ok(json!({"imageDataUrl":format!("data:{mime};base64,{}", STANDARD.encode(bytes)), "attachment":null}))
}
