//! Runtime-independent chat transport shared by desktop commands and the Linux local service.
use crate::modules::chat_auth::{
    SignedRequestHeaders, CHAT_KEY_ID_HEADER, CHAT_NONCE_HEADER, CHAT_SIGNATURE_HEADER,
    CHAT_TIMESTAMP_HEADER,
};

use crate::modules::chat_service::{
    is_message_id_for_player, valid_attachment_meta, ChatAttachmentMeta,
    ChatMessage as ChatServiceMessage, ChatPeerIdentity, MessageType, SendMessageRequest,
    CHAT_TOKEN_HEADER, MAX_ANNOUNCE_BYTES, MAX_AVATAR_BYTES, MAX_CLIPBOARD_BYTES,
    MAX_FILE_CONTENT_BYTES, MAX_HISTORY_MESSAGES, MAX_IMAGE_BYTES, MAX_IMAGE_CONTENT_BYTES,
    MAX_RECALL_BYTES, MAX_TEXT_BYTES, MAX_TODO_BYTES, MAX_VOICE_GROUP_BYTES, MAX_WHITEBOARD_BYTES,
    RECALL_WINDOW_SECS,
};

use crate::modules::chat_service::ChatService;
use std::sync::Arc;
use tokio::sync::Mutex;
pub const MAX_CHAT_TARGETS: usize = 64;
pub const MAX_CHAT_RESPONSE_BYTES: usize = 16 * 1024 * 1024;

pub(crate) fn with_chat_signature(
    request: reqwest::RequestBuilder,
    signed: &SignedRequestHeaders,
) -> reqwest::RequestBuilder {
    request
        .header(CHAT_KEY_ID_HEADER, &signed.key_id)
        .header(CHAT_SIGNATURE_HEADER, &signed.signature)
        .header(CHAT_TIMESTAMP_HEADER, &signed.timestamp)
        .header(CHAT_NONCE_HEADER, &signed.nonce)
}

pub(crate) fn chat_http_host(raw: &str) -> Result<String, String> {
    let ip = raw
        .parse::<std::net::IpAddr>()
        .map_err(|_| "聊天目标不是有效的虚拟IP".to_string())?;
    if ip.is_unspecified() || ip.is_loopback() || ip.is_multicast() {
        return Err("聊天目标IP不在允许范围内".to_string());
    }
    Ok(match ip {
        std::net::IpAddr::V4(ip) => ip.to_string(),
        std::net::IpAddr::V6(ip) => format!("[{}]", ip),
    })
}

pub(crate) fn current_unix_seconds() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}

pub(crate) fn validate_outgoing_chat_payload(
    message_type: &MessageType,
    content: &str,
    image_data: Option<&Vec<u8>>,
    local_player_id: &str,
    local_is_host: bool,
    local_messages: &[ChatServiceMessage],
) -> Result<(), String> {
    let content_bytes = content.as_bytes().len();
    match message_type {
        MessageType::Voice => {
            if !crate::modules::chat_service::valid_voice_payload(
                content,
                image_data.map(|v| v.as_slice()),
            ) {
                return Err("语音消息格式或时长无效".into());
            }
        }
        MessageType::File => {
            if content_bytes == 0 || content_bytes > MAX_FILE_CONTENT_BYTES || image_data.is_some()
            {
                return Err("文件消息元数据无效".to_string());
            }
            let meta: ChatAttachmentMeta =
                serde_json::from_str(content).map_err(|_| "文件消息元数据无法解析".to_string())?;
            if !valid_attachment_meta(&meta) {
                return Err("文件消息元数据无效".to_string());
            }
        }
        MessageType::Text => {
            if content_bytes == 0 || content_bytes > MAX_TEXT_BYTES || image_data.is_some() {
                return Err("文本消息为空、过长或包含多余图片数据".to_string());
            }
        }
        MessageType::Image => {
            if content_bytes > MAX_IMAGE_CONTENT_BYTES {
                return Err("图片消息说明过长".to_string());
            }
            let image = image_data.ok_or_else(|| "图片消息缺少图片数据".to_string())?;
            if image.is_empty() || image.len() > MAX_IMAGE_BYTES {
                return Err("图片数据大小无效".to_string());
            }
        }
        MessageType::Announce => {
            if content_bytes > MAX_ANNOUNCE_BYTES || image_data.is_some() {
                return Err("公告消息大小或数据类型无效".to_string());
            }
            if !local_is_host {
                return Err("只有房主可以发送公告".to_string());
            }
        }
        MessageType::VoiceGroup => {
            if content_bytes > MAX_VOICE_GROUP_BYTES || image_data.is_some() {
                return Err("语音小队消息大小或数据类型无效".to_string());
            }
            let group = content
                .parse::<u8>()
                .map_err(|_| "语音小队编号无效".to_string())?;
            if group > 4 {
                return Err("语音小队编号超出范围".to_string());
            }
        }
        MessageType::Clipboard => {
            if content_bytes > MAX_CLIPBOARD_BYTES || image_data.is_some() {
                return Err("剪贴板消息大小或数据类型无效".to_string());
            }
        }
        MessageType::Todo => {
            if content_bytes > MAX_TODO_BYTES || image_data.is_some() {
                return Err("待办消息大小或数据类型无效".to_string());
            }
        }
        MessageType::Whiteboard => {
            if content_bytes > MAX_WHITEBOARD_BYTES || image_data.is_some() {
                return Err("白板消息大小或数据类型无效".to_string());
            }
        }
        MessageType::Recall => {
            if content_bytes == 0 || content_bytes > MAX_RECALL_BYTES || image_data.is_some() {
                return Err("撤回消息参数无效".to_string());
            }
            let target = local_messages
                .iter()
                .find(|message| message.id == content)
                .ok_or_else(|| "只能撤回本地已知消息".to_string())?;
            if target.player_id != local_player_id
                || current_unix_seconds().saturating_sub(target.timestamp) > RECALL_WINDOW_SECS
            {
                return Err("只能撤回自己两分钟内发送的消息".to_string());
            }
        }
        MessageType::Avatar => {
            if content_bytes > MAX_AVATAR_BYTES || image_data.is_some() {
                return Err("头像消息大小或数据类型无效".to_string());
            }
            if !content.is_empty() && !content.starts_with("data:image/") {
                return Err("头像消息必须使用图片 data URL".to_string());
            }
        }
    }
    Ok(())
}

pub(crate) async fn read_remote_body_limited(
    response: reqwest::Response,
    limit: usize,
) -> Result<Vec<u8>, String> {
    use futures_util::StreamExt;

    if response
        .content_length()
        .is_some_and(|length| length > limit as u64)
    {
        return Err("远程响应超过元数据大小限制".to_string());
    }

    let mut body = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| format!("读取远程响应失败: {}", error))?;
        let next_len = body
            .len()
            .checked_add(chunk.len())
            .ok_or_else(|| "远程响应大小溢出".to_string())?;
        if next_len > limit {
            return Err("远程响应超过元数据大小限制".to_string());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

pub async fn send_p2p_chat_message(
    player_id: String,
    player_name: String,
    content: String,
    message_type: String,
    image_data: Option<Vec<u8>>,
    message_id: Option<String>,
    recipient_id: Option<String>,
    peer_ips: Vec<String>,
    chat_service: Arc<Mutex<ChatService>>,
) -> Result<serde_json::Value, String> {
    let _ = player_name;
    if peer_ips.len() > MAX_CHAT_TARGETS {
        return Err("聊天目标数量超过限制".to_string());
    }

    let chat_svc = chat_service.lock().await;

    // The renderer supplies these fields for wire compatibility only. The
    // active chat session is the authority for both identity and targets.
    let local_identity = chat_svc
        .get_local_identity()
        .ok_or_else(|| "聊天会话尚未初始化".to_string())?;
    if player_id != local_identity.player_id {
        return Err("聊天发送者身份与当前会话不匹配".to_string());
    }
    let chat_token = chat_svc
        .get_chat_token()
        .ok_or_else(|| "聊天令牌尚未就绪".to_string())?;
    let local_is_host = chat_svc.local_is_host();

    let msg_type = match message_type.as_str() {
        "text" => MessageType::Text,
        "image" => MessageType::Image,
        "voice" => MessageType::Voice,
        "file" => MessageType::File,
        "announce" => MessageType::Announce,
        "voicegroup" => MessageType::VoiceGroup,
        "clipboard" => MessageType::Clipboard,
        "todo" => MessageType::Todo,
        "whiteboard" => MessageType::Whiteboard,
        "recall" => MessageType::Recall,
        "avatar" => MessageType::Avatar,
        _ => return Err("聊天消息类型无效".to_string()),
    };

    let local_messages = chat_svc.get_local_messages(None);
    validate_outgoing_chat_payload(
        &msg_type,
        &content,
        image_data.as_ref(),
        &local_identity.player_id,
        local_is_host,
        &local_messages,
    )?;
    if matches!(msg_type, MessageType::File) {
        let meta: ChatAttachmentMeta =
            serde_json::from_str(&content).map_err(|_| "文件消息元数据无法解析".to_string())?;
        if !chat_svc.has_attachment(&meta.id, recipient_id.as_deref()) {
            return Err("文件附件未注册或收件人不匹配".to_string());
        }
    }

    let message_id = message_id
        .filter(|id| !id.is_empty())
        .unwrap_or_else(|| format!("msg-{}-{}", local_identity.player_id, uuid::Uuid::new_v4()));
    if !is_message_id_for_player(&message_id, &local_identity.player_id) {
        return Err("聊天消息ID无效".to_string());
    }

    // The renderer may request a subset for UI reasons, but it never chooses
    // network destinations. Broadcast to the authoritative signaling roster.
    let mut authoritative_peers = chat_svc.authoritative_peers();
    if let Some(target) = recipient_id.as_ref() {
        if !matches!(
            msg_type,
            MessageType::Text
                | MessageType::Image
                | MessageType::Voice
                | MessageType::File
                | MessageType::Recall
        ) {
            return Err("此消息类型不支持私聊".to_string());
        }
        authoritative_peers.retain(|peer| &peer.player_id == target);
        if authoritative_peers.len() != 1 {
            return Err("私聊对象已离开大厅".to_string());
        }
    }

    let message = ChatServiceMessage {
        id: message_id.clone(),
        player_id: local_identity.player_id.clone(),
        player_name: local_identity.player_name.clone(),
        content: content.clone(),
        message_type: msg_type.clone(),
        timestamp: current_unix_seconds(),
        image_data: image_data.clone(),
        recipient_id: recipient_id.clone(),
    };

    // Keep an origin copy of every validated message. Remote history fetches
    // accept only messages authored by the queried peer, preventing one peer
    // from forging another member's history.
    if !chat_svc.add_local_message(message) {
        return Err("聊天消息ID重复或本地历史已满".to_string());
    }

    drop(chat_svc);

    log::info!(
        "📤 [ChatService] 向 {} 个已授权玩家发送 {} 字节消息",
        authoritative_peers.len(),
        content.as_bytes().len()
    );

    let total = authoritative_peers.len();

    // 【优化】使用并发发送，提高图片传输速度
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10)) // 设置超时
        .connect_timeout(std::time::Duration::from_secs(3))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|e| format!("创建HTTP客户端失败: {}", e))?;

    let mut tasks = Vec::new();

    for peer in authoritative_peers {
        let peer_ip = peer.virtual_ip.clone();
        let host = chat_http_host(&peer_ip)?;
        let url = format!("http://{}:14540/api/chat/send", host);
        let request = SendMessageRequest {
            id: Some(message_id.clone()),
            player_id: local_identity.player_id.clone(),
            player_name: local_identity.player_name.clone(),
            content: content.clone(),
            message_type: msg_type.clone(),
            image_data: image_data.clone(),
            recipient_id: recipient_id.clone(),
        };
        // Serialize once and send those exact bytes, because the signature
        // covers a digest of the body: letting reqwest re-serialize could in
        // principle emit different bytes and break verification.
        let body = serde_json::to_vec(&request)
            .map_err(|error| format!("序列化聊天消息失败: {}", error))?;
        let body = chat_service
            .lock()
            .await
            .encrypt_for_peer(&peer, "/api/chat/send", &body)?;

        let client_clone = client.clone();
        let url_clone = url.clone();
        let chat_token_clone = chat_token.clone();
        let chat_service_clone = Arc::clone(&chat_service);
        let audience = peer_ip.clone();

        // 创建并发任务，返回是否送达成功（带一次快速重试，降低瞬时抖动导致的漏发）
        let task = tokio::spawn(async move {
            for attempt in 0..2 {
                // Sign per attempt: the nonce is single-use at the receiver, so
                // reusing one signature for the retry would look like a replay.
                let signed = match chat_service_clone.lock().await.sign_request(
                    "POST",
                    "/api/chat/send",
                    &audience,
                    &body,
                ) {
                    Some(signed) => signed,
                    None => {
                        log::warn!("⚠️ 聊天签名不可用，放弃发送到 {}", url_clone);
                        return false;
                    }
                };
                let start = std::time::Instant::now();
                match with_chat_signature(
                    client_clone
                        .post(&url_clone)
                        .header(CHAT_TOKEN_HEADER, &chat_token_clone)
                        .header(reqwest::header::CONTENT_TYPE, "application/json")
                        .body(body.clone()),
                    &signed,
                )
                .send()
                .await
                {
                    Ok(response) => {
                        let elapsed = start.elapsed();
                        if response.status().is_success() {
                            log::info!(
                                "✅ 消息已发送到: {} (耗时: {:?}, 第{}次)",
                                url_clone,
                                elapsed,
                                attempt + 1
                            );
                            return true;
                        } else {
                            log::warn!(
                                "⚠️ 发送消息失败 ({}): HTTP {} (第{}次)",
                                url_clone,
                                response.status(),
                                attempt + 1
                            );
                        }
                    }
                    Err(e) => {
                        let elapsed = start.elapsed();
                        log::warn!(
                            "⚠️ 发送消息失败 ({}, 耗时: {:?}, 第{}次): {}",
                            url_clone,
                            elapsed,
                            attempt + 1,
                            e
                        );
                    }
                }
                if attempt == 0 {
                    // 第一次失败后稍等再重试一次
                    tokio::time::sleep(std::time::Duration::from_millis(400)).await;
                }
            }
            false
        });

        tasks.push(task);
    }

    // 等待所有发送完成，统计送达数量（用于给前端回执）
    let mut delivered = 0usize;
    for task in tasks {
        if let Ok(true) = task.await {
            delivered += 1;
        }
    }
    log::info!(
        "🎉 [ChatService] 消息发送完成：送达 {}/{}",
        delivered,
        total
    );

    Ok(serde_json::json!({ "delivered": delivered, "total": total, "messageId": message_id }))
}

pub(crate) fn is_safe_remote_chat_message(
    message: &ChatServiceMessage,
    expected: &ChatPeerIdentity,
    host_id: Option<&str>,
) -> bool {
    if message.player_id != expected.player_id
        || message.player_name != expected.player_name
        || !is_message_id_for_player(&message.id, &message.player_id)
    {
        return false;
    }
    let content_bytes = message.content.as_bytes().len();
    let shape_is_valid = match message.message_type {
        MessageType::Voice => crate::modules::chat_service::valid_voice_payload(
            &message.content,
            message.image_data.as_deref(),
        ),
        MessageType::File => {
            content_bytes > 0
                && content_bytes <= MAX_FILE_CONTENT_BYTES
                && message.image_data.is_none()
                && serde_json::from_str::<ChatAttachmentMeta>(&message.content)
                    .is_ok_and(|meta| valid_attachment_meta(&meta))
        }
        MessageType::Text => {
            content_bytes > 0 && content_bytes <= MAX_TEXT_BYTES && message.image_data.is_none()
        }
        MessageType::Image => {
            content_bytes <= MAX_IMAGE_CONTENT_BYTES
                && message
                    .image_data
                    .as_ref()
                    .is_some_and(|image| !image.is_empty() && image.len() <= MAX_IMAGE_BYTES)
        }
        MessageType::Announce => {
            content_bytes <= MAX_ANNOUNCE_BYTES
                && message.image_data.is_none()
                && host_id == Some(message.player_id.as_str())
        }
        MessageType::VoiceGroup => {
            content_bytes <= MAX_VOICE_GROUP_BYTES
                && message.image_data.is_none()
                && message.content.parse::<u8>().is_ok_and(|group| group <= 4)
        }
        MessageType::Clipboard => {
            content_bytes <= MAX_CLIPBOARD_BYTES && message.image_data.is_none()
        }
        MessageType::Todo => content_bytes <= MAX_TODO_BYTES && message.image_data.is_none(),
        MessageType::Whiteboard => {
            content_bytes <= MAX_WHITEBOARD_BYTES && message.image_data.is_none()
        }
        MessageType::Recall => {
            content_bytes > 0 && content_bytes <= MAX_RECALL_BYTES && message.image_data.is_none()
        }
        MessageType::Avatar => {
            content_bytes <= MAX_AVATAR_BYTES
                && message.image_data.is_none()
                && (message.content.is_empty() || message.content.starts_with("data:image/"))
        }
    };
    shape_is_valid
        && serde_json::to_vec(message).is_ok_and(|encoded| encoded.len() <= MAX_CHAT_RESPONSE_BYTES)
}

/// 获取P2P聊天消息
///
/// # 参数
/// * `peer_ips` - 玩家的虚拟IP列表
/// * `since` - 获取此时间戳之后的消息（可选）
///
/// # 返回
/// * `Ok(Vec<ChatMessage>)` - 消息列表
/// * `Err(String)` - 错误信息
pub async fn get_p2p_chat_messages(
    peer_ips: Vec<String>,
    since: Option<u64>,
    chat_service: Arc<Mutex<ChatService>>,
) -> Result<Vec<ChatServiceMessage>, String> {
    if peer_ips.len() > MAX_CHAT_TARGETS {
        return Err("聊天目标数量超过限制".to_string());
    }
    let chat_svc = chat_service.lock().await;
    let mut all_messages = chat_svc.get_local_messages(since);
    let authoritative_peers = chat_svc.authoritative_peers();
    let chat_token = chat_svc
        .get_chat_token()
        .ok_or_else(|| "聊天令牌尚未就绪".to_string())?;
    let host_id = chat_svc.get_host_id();
    drop(chat_svc);

    log::info!(
        "📥 [ChatService] 从 {} 个权威玩家获取消息",
        authoritative_peers.len()
    );

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(3))
        .connect_timeout(std::time::Duration::from_millis(800))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|e| format!("创建HTTP客户端失败: {}", e))?;

    let mut tasks = Vec::new();
    for peer in authoritative_peers {
        let host = chat_http_host(&peer.virtual_ip)?;
        let url = if let Some(ts) = since {
            format!("http://{}:14540/api/chat/messages?since={}", host, ts)
        } else {
            format!("http://{}:14540/api/chat/messages", host)
        };
        let client_clone = client.clone();
        let token = chat_token.clone();
        let expected = peer.clone();
        let expected_ip = peer.virtual_ip.clone();
        let expected_host_id = host_id.clone();
        let chat_service_clone = Arc::clone(&chat_service);
        let audience = peer.virtual_ip.clone();
        tasks.push(tokio::spawn(async move {
            // History reads are signed with an empty body: the query string is
            // not covered, so the receiver treats `since` as a filter hint only
            // and never as an authorization input.
            let signed = match chat_service_clone.lock().await.sign_request(
                "GET",
                "/api/chat/messages",
                &audience,
                &[],
            ) {
                Some(signed) => signed,
                None => {
                    log::warn!("⚠️ 聊天签名不可用，跳过历史拉取 ({})", expected_ip);
                    return Vec::new();
                }
            };
            match with_chat_signature(
                client_clone.get(&url).header(CHAT_TOKEN_HEADER, token),
                &signed,
            )
            .send()
            .await
            {
                Ok(response) => {
                    if response.status().is_success() {
                        let body = match read_remote_body_limited(response, MAX_CHAT_RESPONSE_BYTES)
                            .await
                        {
                            Ok(body) => body,
                            Err(error) => {
                                log::warn!("⚠️ 聊天历史响应超限 ({}): {}", expected_ip, error);
                                return Vec::new();
                            }
                        };
                        let Ok(body) = chat_service_clone.lock().await.decrypt_from_peer(
                            &expected,
                            "/api/chat/messages",
                            &body,
                        ) else {
                            return Vec::new();
                        };
                        let Ok(mut messages) =
                            serde_json::from_slice::<Vec<ChatServiceMessage>>(&body)
                        else {
                            log::warn!("⚠️ 聊天历史 JSON 无效 ({})", expected_ip);
                            return Vec::new();
                        };
                        if messages.len() > MAX_HISTORY_MESSAGES {
                            log::warn!("⚠️ 聊天历史条数超限 ({})", expected_ip);
                            return Vec::new();
                        }
                        messages.retain(|message| {
                            is_safe_remote_chat_message(
                                message,
                                &expected,
                                expected_host_id.as_deref(),
                            )
                        });
                        log::debug!("✅ 从 {} 获取到 {} 条本人消息", expected_ip, messages.len());
                        messages
                    } else {
                        log::warn!(
                            "⚠️ HTTP请求失败 ({}): 状态码 {}",
                            expected_ip,
                            response.status()
                        );
                        Vec::new()
                    }
                }
                Err(e) => {
                    log::debug!("⚠️ 获取消息失败 ({}): {}", expected_ip, e);
                    Vec::new()
                }
            }
        }));
    }

    for task in tasks {
        if let Ok(messages) = task.await {
            all_messages.extend(messages);
        }
    }

    all_messages.sort_by_key(|msg| msg.timestamp);
    let mut seen_ids = std::collections::HashSet::new();
    all_messages.retain(|msg| seen_ids.insert(msg.id.clone()));

    Ok(all_messages)
}
