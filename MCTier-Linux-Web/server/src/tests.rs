use super::*;
use axum::body::Body;
use axum::http::Request as HttpRequest;
use futures_util::StreamExt;
use modules::chat_service::{ChatMessage, MessageType};
use tower::ServiceExt;

#[tokio::test]
async fn builtin_emoji_pack_is_embedded_and_served_as_gif() {
    let manifest: Value =
        serde_json::from_str(include_str!("../../../shared/builtin-emoji/manifest.json")).unwrap();
    let ids = manifest["ids"].as_array().unwrap();
    assert_eq!(ids.len(), manifest["count"].as_u64().unwrap() as usize);
    let embedded: Vec<_> = ASSETS
        .iter()
        .filter(|(path, _)| path.starts_with("/builtin-emoji/") && path.ends_with(".gif"))
        .collect();
    assert_eq!(embedded.len(), ids.len());
    for id in ids {
        let path = format!("/builtin-emoji/{}.gif", id.as_str().unwrap());
        let (_, bytes) = ASSETS
            .iter()
            .find(|(key, _)| *key == path)
            .expect("all manifest GIFs must be embedded");
        assert!(bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a"));
    }
    let path = format!("/builtin-emoji/{}.gif", ids[0].as_str().unwrap());
    let response = router(app())
        .oneshot(
            HttpRequest::builder()
                .uri(path)
                .header("host", "127.0.0.1:14700")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()[header::CONTENT_TYPE], "image/gif");
    assert_eq!(
        response.headers()[header::X_CONTENT_TYPE_OPTIONS],
        "nosniff"
    );
}

#[tokio::test]
async fn browser_attachment_download_requires_origin_nonce_session_and_registered_metadata() {
    let app = app();
    let meta =
        json!({"id":"attachment-123456", "name":"example.txt", "mime":"text/plain", "size":3});
    let mut req = request(
        &app,
        "/api/chat/attachment",
        Some(json!({"ownerPlayerId":"any", "attachment":meta})),
    );
    req.headers_mut().remove("x-mctier-csrf");
    assert_eq!(
        router(app.clone()).oneshot(req).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
    let id = {
        let chat = app.chat.lock().await;
        chat.set_virtual_ip("10.126.126.2".into());
        let (id, _) = chat.signaling_identity().unwrap();
        chat.set_session(
            "a".repeat(64),
            1,
            id.clone(),
            "Local".into(),
            Some(id.clone()),
            vec![],
        )
        .unwrap();
        id
    };
    for body in [
        json!({"ownerPlayerId":id, "attachment":meta, "path":"/etc/passwd"}),
        json!({"ownerPlayerId":id, "attachment":{ "id":"../../passwd", "name":"example.txt", "mime":"text/plain", "size":3}}),
        json!({"ownerPlayerId":"unknown-peer", "attachment":meta}),
        json!({"ownerPlayerId":id, "attachment":meta}),
    ] {
        let status = router(app.clone())
            .oneshot(request(&app, "/api/chat/attachment", Some(body)))
            .await
            .unwrap()
            .status();
        assert!(matches!(
            status,
            StatusCode::BAD_REQUEST | StatusCode::UNPROCESSABLE_ENTITY
        ));
    }
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("registered.txt");
    tokio::fs::write(&path, b"abc").await.unwrap();
    app.chat
        .lock()
        .await
        .register_attachment(serde_json::from_value(meta.clone()).unwrap(), path, None)
        .unwrap();
    let response = router(app.clone())
        .oneshot(request(
            &app,
            "/api/chat/attachment",
            Some(json!({"ownerPlayerId":id, "attachment":meta})),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers()[header::CONTENT_TYPE],
        "application/octet-stream"
    );
    assert_eq!(
        response.headers()[header::CONTENT_DISPOSITION],
        "attachment"
    );
    assert_eq!(
        &axum::body::to_bytes(response.into_body(), 10)
            .await
            .unwrap()[..],
        b"abc"
    );
}

fn app() -> Arc<App> {
    App::new(std::path::PathBuf::from("/missing-test-core"))
}

#[tokio::test]
async fn network_privilege_operations_require_preview_nonce_and_active_room() {
    let app = app();
    for (command, args) in [
        ("apply_network_operation", json!({"token":"invented"})),
        (
            "prepare_firewall_repair",
            json!({"backend":"ufw","zone":"","ephemeralUdp":false}),
        ),
        ("scan_minecraft_servers", json!({"port":25565})),
        (
            "validate_network_settings",
            json!({"listeners":["tcp://0.0.0.0:14700"]}),
        ),
    ] {
        let response = router(app.clone())
            .oneshot(request(
                &app,
                "/api/invoke",
                Some(json!({"command":command,"args":args})),
            ))
            .await
            .unwrap();
        assert!(
            matches!(
                response.status(),
                StatusCode::BAD_REQUEST | StatusCode::UNPROCESSABLE_ENTITY
            ),
            "{command}"
        );
    }
    let mut req = request(
        &app,
        "/api/invoke",
        Some(json!({"command":"apply_network_operation","args":{"token":"invented"}})),
    );
    req.headers_mut().remove("x-mctier-csrf");
    assert_eq!(
        router(app.clone()).oneshot(req).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
    assert!(app.runtime.lock().await.session.is_none());
}
fn request(app: &App, uri: &str, body: Option<Value>) -> HttpRequest<Body> {
    let mut builder = HttpRequest::builder()
        .uri(uri)
        .header("host", "127.0.0.1:14700")
        .header("x-mctier-client", "a".repeat(32))
        .header("x-mctier-csrf", &app.csrf);
    if let Some(body) = body {
        builder = builder
            .method("POST")
            .header("origin", ORIGIN)
            .header("content-type", "application/json");
        builder.body(Body::from(body.to_string())).unwrap()
    } else {
        builder.body(Body::empty()).unwrap()
    }
}

#[tokio::test]
async fn security_host_origin_fetch_site_and_csrf_are_enforced_before_mutation() {
    for (key, val) in [
        ("host", "evil.example"),
        ("origin", "http://evil.example"),
        ("sec-fetch-site", "cross-site"),
        ("x-mctier-csrf", "wrong"),
    ] {
        let app = app();
        let mut request = request(
            &app,
            "/api/invoke",
            Some(json!({"command":"leave_lobby","args":{}})),
        );
        request
            .headers_mut()
            .insert(key, HeaderValue::from_str(val).unwrap());
        assert_eq!(
            router(app.clone()).oneshot(request).await.unwrap().status(),
            StatusCode::FORBIDDEN
        );
        assert!(app.runtime.lock().await.owner.is_none());
    }
    let app = app();
    let mut req = request(&app, "/api/lease", Some(json!({})));
    req.headers_mut().remove("origin");
    assert_eq!(
        router(app).oneshot(req).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn security_static_lookup_cannot_read_local_paths_or_symlinks() {
    let app = app();
    for path in [
        "/../../etc/passwd",
        "/%2e%2e/%2e%2e/etc/passwd",
        "/src-tauri/Cargo.toml",
        "/api/unknown",
        "/server/src/main.rs",
    ] {
        let response = router(app.clone())
            .oneshot(request(&app, path, None))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
    let response = router(app.clone())
        .oneshot(request(&app, "/", None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert!(response
        .headers()
        .get(header::CONTENT_SECURITY_POLICY)
        .unwrap()
        .to_str()
        .unwrap()
        .contains("frame-ancestors 'none'"));
}

#[tokio::test]
async fn security_independent_tabs_cannot_take_over_the_active_controller() {
    let app = app();
    app.claim(&"a".repeat(32)).await.unwrap();
    let mut req = request(
        &app,
        "/api/invoke",
        Some(json!({"command":"leave_lobby","args":{}})),
    );
    req.headers_mut().insert(
        "x-mctier-client",
        HeaderValue::from_str(&"b".repeat(32)).unwrap(),
    );
    assert_eq!(
        router(app.clone()).oneshot(req).await.unwrap().status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        app.runtime.lock().await.owner.as_deref(),
        Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    );
    app.leave().await;
    app.claim(&"b".repeat(32)).await.unwrap();
}

#[tokio::test]
async fn security_unknown_commands_and_registration_outside_a_lobby_fail_closed() {
    let app = app();
    for (command, args, status) in [
        (
            "read_file",
            json!({"path":"/etc/passwd"}),
            StatusCode::NOT_IMPLEMENTED,
        ),
        (
            "sign_signaling_registration",
            json!({"challenge":"a".repeat(64),"lobbyName":"room","virtualIp":"10.126.126.2"}),
            StatusCode::BAD_REQUEST,
        ),
    ] {
        let response = router(app.clone())
            .oneshot(request(
                &app,
                "/api/invoke",
                Some(json!({"command":command,"args":args})),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), status);
    }
    let response = router(app.clone())
        .oneshot(request(&app, "/api/status", None))
        .await
        .unwrap();
    let body = axum::body::to_bytes(response.into_body(), 65536)
        .await
        .unwrap();
    let status: Value = serde_json::from_slice(&body).unwrap();
    assert!(status["session"].is_null());
    assert_eq!(status["network"]["state"], "stopped");
    assert!(!std::str::from_utf8(&body)
        .unwrap()
        .contains("/missing-test-core"));
}

#[tokio::test]
async fn local_chat_stream_requires_current_token_and_ends_on_session_cleanup() {
    let app = app();
    let token = "a".repeat(64);
    let id = {
        let chat = app.chat.lock().await;
        chat.set_virtual_ip("10.126.126.2".into());
        let (id, _) = chat.signaling_identity().unwrap();
        chat.set_session(
            token.clone(),
            1,
            id.clone(),
            "Local".into(),
            Some(id.clone()),
            vec![],
        )
        .unwrap();
        id
    };
    let mut wrong = request(&app, "/api/chat/stream", None);
    wrong
        .headers_mut()
        .insert("x-mctier-chat-token", HeaderValue::from_static("wrong"));
    assert_eq!(
        router(app.clone()).oneshot(wrong).await.unwrap().status(),
        StatusCode::UNAUTHORIZED
    );
    let mut req = request(&app, "/api/chat/stream", None);
    req.headers_mut().insert(
        "x-mctier-chat-token",
        HeaderValue::from_str(&token).unwrap(),
    );
    let response = router(app.clone()).oneshot(req).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let mut body = response.into_body().into_data_stream();
    let ready = tokio::time::timeout(Duration::from_millis(200), body.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(std::str::from_utf8(&ready).unwrap().contains(": ready"));
    app.chat.lock().await.add_local_message(ChatMessage {
        id: format!("msg-{id}-test"),
        player_id: id,
        player_name: "Local".into(),
        content: "hello".into(),
        message_type: MessageType::Text,
        timestamp: 1,
        image_data: None,
        recipient_id: None,
    });
    let data = tokio::time::timeout(Duration::from_secs(1), body.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(std::str::from_utf8(&data).unwrap().contains("hello"));
    app.leave().await;
    assert!(tokio::time::timeout(Duration::from_secs(1), body.next())
        .await
        .unwrap()
        .is_none());
    assert!(app.chat.lock().await.get_chat_token().is_none());
}

#[tokio::test]
async fn startup_requests_never_initialize_identity_or_start_a_network() {
    let app = app();
    for path in ["/", "/healthz", "/api/bootstrap", "/api/status"] {
        assert_eq!(
            router(app.clone())
                .oneshot(request(&app, path, None))
                .await
                .unwrap()
                .status(),
            StatusCode::OK
        );
    }
    assert!(app.chat.lock().await.signing_public_key().is_none());
    assert!(app.runtime.lock().await.session.is_none());
    assert!(app.runtime.lock().await.owner.is_none());
}

#[tokio::test]
async fn expired_browser_lease_cleans_up_and_a_renewed_lease_is_preserved() {
    let app = app();
    let owner = "a".repeat(32);
    app.claim(&owner).await.unwrap();
    app.runtime.lock().await.last_lease =
        std::time::Instant::now() - runtime::LEASE_TTL - Duration::from_secs(1);
    app.claim(&owner).await.unwrap();
    app.maintenance().await;
    assert_eq!(
        app.runtime.lock().await.owner.as_deref(),
        Some(owner.as_str())
    );
    app.runtime.lock().await.last_lease =
        std::time::Instant::now() - runtime::LEASE_TTL - Duration::from_secs(1);
    app.maintenance().await;
    assert!(app.runtime.lock().await.owner.is_none());
}

#[tokio::test]
async fn local_history_is_authenticated_bounded_session_data_and_cleans_up() {
    let app = app();
    let command = |since: Option<u64>| json!({"command":"get_local_p2p_chat_messages","args":{"since":since}});
    assert_eq!(
        router(app.clone())
            .oneshot(request(&app, "/api/invoke", Some(command(None))))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST
    );
    {
        let chat = app.chat.lock().await;
        chat.set_virtual_ip("10.126.126.2".into());
        let (id, _) = chat.signaling_identity().unwrap();
        chat.set_session(
            "a".repeat(64),
            1,
            id.clone(),
            "Local".into(),
            Some(id),
            vec![],
        )
        .unwrap();
        chat.add_local_message(ChatMessage {
            id: "msg-remote-test".into(),
            player_id: "remote".into(),
            player_name: "Phone".into(),
            content: "received locally".into(),
            message_type: MessageType::Text,
            timestamp: 2,
            image_data: None,
            recipient_id: None,
        });
    }
    let mut forbidden = request(&app, "/api/invoke", Some(command(None)));
    forbidden.headers_mut().remove("x-mctier-csrf");
    assert_eq!(
        router(app.clone())
            .oneshot(forbidden)
            .await
            .unwrap()
            .status(),
        StatusCode::FORBIDDEN
    );
    for (since, count) in [(None, 1), (Some(2), 0)] {
        let response = router(app.clone())
            .oneshot(request(&app, "/api/invoke", Some(command(since))))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let data: Value = serde_json::from_slice(
            &axum::body::to_bytes(response.into_body(), 65536)
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(data.as_array().unwrap().len(), count);
        if count == 1 {
            assert_eq!(data[0]["message_type"], "text");
            assert_eq!(data[0]["player_name"], "Phone");
        }
    }
    let status = app.status().await;
    assert_eq!(status["chatReceive"]["storedRemoteMessages"], 1);
    assert!(!status.to_string().contains("received locally"));
    let mut other = request(&app, "/api/invoke", Some(command(None)));
    other.headers_mut().insert(
        "x-mctier-client",
        HeaderValue::from_str(&"b".repeat(32)).unwrap(),
    );
    assert_eq!(
        router(app.clone()).oneshot(other).await.unwrap().status(),
        StatusCode::CONFLICT
    );
    app.leave().await;
    assert_eq!(app.status().await["chatReceive"]["storedRemoteMessages"], 0);
    assert_eq!(
        router(app.clone())
            .oneshot(request(&app, "/api/invoke", Some(command(None))))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn upload_is_authenticated_bounded_and_session_scoped() {
    let app = app();
    let chat = app.chat.lock().await;
    chat.set_virtual_ip("10.126.126.2".into());
    let (id, _) = chat.signaling_identity().unwrap();
    chat.set_session(
        "b".repeat(64),
        1,
        id.clone(),
        "Local".into(),
        Some(id.clone()),
        vec![],
    )
    .unwrap();
    drop(chat);
    let upload_req = |uri: &str, data: Vec<u8>| {
        let mut req = request(&app, uri, Some(json!({})));
        req.headers_mut().insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static("application/octet-stream"),
        );
        *req.body_mut() = Body::from(data);
        req
    };
    let uri = "/api/chat/upload?name=example.txt&mime=text%2Fplain";
    let mut req = upload_req(uri, b"abc".to_vec());
    req.headers_mut().remove("x-mctier-csrf");
    assert_eq!(
        router(app.clone()).oneshot(req).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
    for (uri, bytes) in [
        (
            "/api/chat/upload?name=..%2Fsecret&mime=text%2Fplain",
            b"abc".to_vec(),
        ),
        (
            "/api/chat/upload?name=a&mime=text%2Fplain&path=%2Fetc%2Fpasswd",
            b"abc".to_vec(),
        ),
        (
            "/api/chat/upload?name=a&mime=text%2Fplain&recipientId=unknown",
            b"abc".to_vec(),
        ),
        (uri, vec![]),
    ] {
        assert!(!router(app.clone())
            .oneshot(upload_req(uri, bytes))
            .await
            .unwrap()
            .status()
            .is_success());
    }
    // Raw upload is independent of the 16 MiB invoke JSON budget.
    let response = router(app.clone())
        .oneshot(upload_req(uri, vec![7; 17 * 1024 * 1024]))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let meta: modules::chat_service::ChatAttachmentMeta = serde_json::from_slice(
        &axum::body::to_bytes(response.into_body(), 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    let path = app.chat.lock().await.local_attachment_path(&meta).unwrap();
    use std::os::unix::fs::PermissionsExt;
    assert_eq!(
        std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert!(app.chat.lock().await.has_attachment(&meta.id, None));
    assert!(!app
        .chat
        .lock()
        .await
        .has_attachment(&meta.id, Some("other")));
    let response = router(app.clone())
        .oneshot(request(
            &app,
            "/api/chat/attachment",
            Some(json!({"ownerPlayerId":id,"attachment":meta})),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        axum::body::to_bytes(response.into_body(), 18 * 1024 * 1024)
            .await
            .unwrap()
            .len(),
        17 * 1024 * 1024
    );
    assert!(!router(app.clone())
        .oneshot(upload_req(uri, vec![1; 64 * 1024 * 1024 + 1]))
        .await
        .unwrap()
        .status()
        .is_success());
    *app.upload_budget.lock().await = (0, 128);
    assert!(!router(app.clone())
        .oneshot(upload_req(uri, vec![1]))
        .await
        .unwrap()
        .status()
        .is_success());
    app.leave().await;
    assert!(!path.exists());
    assert_eq!(*app.upload_budget.lock().await, (0, 0));
    assert!(!router(app.clone())
        .oneshot(upload_req(uri, vec![1]))
        .await
        .unwrap()
        .status()
        .is_success());
}

#[test]
fn browser_image_preparation_checks_format_and_inline_budget() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    for (bytes, mime) in [
        (b"\x89PNG\r\n\x1a\n".as_slice(), "image/png"),
        (b"GIF89a".as_slice(), "image/gif"),
        (&[0xff, 0xd8, 0xff], "image/jpeg"),
        (b"RIFFxxxxWEBP".as_slice(), "image/webp"),
    ] {
        assert!(
            attachments::prepare_image(&STANDARD.encode(bytes)).unwrap()["imageDataUrl"]
                .as_str()
                .unwrap()
                .starts_with(&format!("data:{mime};base64,"))
        );
    }
    assert!(attachments::prepare_image("bad encoding!").is_err());
    assert!(attachments::prepare_image(&STANDARD.encode(b"<svg></svg>")).is_err());
    assert!(attachments::prepare_image(&STANDARD.encode(vec![1; 2 * 1024 * 1024 + 1])).is_err());
}

#[tokio::test]
async fn delayed_upload_cannot_register_in_a_rejoined_room_with_the_same_token() {
    let app = app();
    let id = {
        let chat = app.chat.lock().await;
        chat.set_virtual_ip("10.126.126.2".into());
        let (id, _) = chat.signaling_identity().unwrap();
        chat.set_session(
            "c".repeat(64),
            1,
            id.clone(),
            "Local".into(),
            Some(id.clone()),
            vec![],
        )
        .unwrap();
        id
    };
    let (began_tx, began_rx) = tokio::sync::oneshot::channel();
    let (continue_tx, continue_rx) = tokio::sync::oneshot::channel();
    let body = Body::from_stream(async_stream::stream! {
        began_tx.send(()).unwrap();
        continue_rx.await.unwrap();
        yield Ok::<bytes::Bytes,Infallible>(bytes::Bytes::from_static(b"abc"));
    });
    let mut req = request(
        &app,
        "/api/chat/upload?name=late.txt&mime=text%2Fplain",
        Some(json!({})),
    );
    *req.body_mut() = body;
    let route = router(app.clone());
    let pending = tokio::spawn(async move { route.oneshot(req).await.unwrap() });
    began_rx.await.unwrap();
    app.leave().await;
    {
        let chat = app.chat.lock().await;
        chat.set_virtual_ip("10.126.126.2".into());
        chat.signaling_identity().unwrap();
        chat.set_session(
            "c".repeat(64),
            1,
            id.clone(),
            "Local".into(),
            Some(id),
            vec![],
        )
        .unwrap();
    }
    // Leaving must cancel the read even if the sender never completes its body.
    let response = tokio::time::timeout(std::time::Duration::from_secs(2), pending)
        .await.unwrap().unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert!(continue_tx.send(()).is_err());
    assert_eq!(*app.upload_budget.lock().await, (0, 0));
}

#[tokio::test]
async fn removed_magic_dns_commands_cannot_read_or_modify_hosts() {
    let app = app();
    for command in ["get_magic_dns_status", "prepare_magic_dns"] {
        let response = router(app.clone()).oneshot(request(
            &app, "/api/invoke", Some(json!({"command":command,"args":{"remove":false}})),
        )).await.unwrap();
        assert_eq!(response.status(), StatusCode::NOT_IMPLEMENTED);
    }
}
