//! Browser-selected snapshots served by the unmodified upstream folder server.
//! Physical paths, lobby credentials and arbitrary remote URLs never enter the API.
use super::*;
use axum::{body::to_bytes, extract::Query};
use modules::file_transfer::{
    FileTransferService, SharedFolder, SharedFolderSummary, LOBBY_TOKEN_HEADER,
};
use std::{collections::HashMap, path::PathBuf};
use tokio::io::AsyncWriteExt;
const MAX_FILE: usize = 64 * 1024 * 1024;
const MAX_TOTAL: u64 = 256 * 1024 * 1024;
const MAX_FILES: usize = 1024;

pub struct Snapshot {
    root: PathBuf,
    files: usize,
    bytes: u64,
    name: String,
    password: Option<String>,
    expires: Option<u64>,
    published: bool,
    touched: std::time::Instant,
}
impl Drop for Snapshot {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}
pub struct Folders {
    pub server: FileTransferService,
    snapshots: HashMap<String, Snapshot>,
}
impl Folders {
    pub fn new() -> Self {
        Self {
            server: FileTransferService::new(),
            snapshots: HashMap::new(),
        }
    }
    pub async fn clear(&mut self) {
        self.server.clear_lobby_token();
        self.server.stop_server().await;
        for share in self.server.get_shares() {
            let _ = self.server.remove_share(&share.id);
        }
        self.server.cleanup_expired_shares();
        self.snapshots.clear();
    }
    pub fn collect(&mut self) {
        self.server.cleanup_expired_shares();
        self.snapshots.retain(|_, s| {
            let expired = s.expires.is_some_and(|v| v <= now());
            !expired && (s.published || s.touched.elapsed() < Duration::from_secs(300))
        });
    }
    pub fn local(&self) -> Vec<SharedFolderSummary> {
        self.server
            .get_shares()
            .iter()
            .map(SharedFolderSummary::from)
            .collect()
    }
    fn bytes(&self) -> u64 {
        self.snapshots.values().map(|s| s.bytes).sum()
    }
    fn count(&self) -> usize {
        self.snapshots.values().map(|s| s.files).sum()
    }
}
fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn segment(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 255
        && s != "."
        && s != ".."
        && !s.chars().any(|c| {
            c.is_control() || matches!(c, '/' | '\\' | ':' | '<' | '>' | '"' | '|' | '?' | '*')
        })
        && !s.ends_with([' ', '.'])
}
fn relative(path: &str, allow_empty: bool) -> Result<(), String> {
    if (allow_empty && path.is_empty())
        || (!path.is_empty() && path.len() <= 1024 && path.split('/').all(segment))
    {
        Ok(())
    } else {
        Err("共享相对路径无效".into())
    }
}
fn destination(root: &std::path::Path, path: &str) -> Result<PathBuf, String> {
    relative(path, false)?;
    let mut parent = root.to_path_buf();
    let parts: Vec<_> = path.split('/').collect();
    for part in std::iter::once("").chain(parts[..parts.len() - 1].iter().copied()) {
        if !part.is_empty() {
            parent.push(part);
        }
        match std::fs::symlink_metadata(&parent) {
            Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => {}
            Ok(_) => return Err("目录层级包含文件或符号链接".into()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound && !part.is_empty() => {
                use std::os::unix::fs::DirBuilderExt;
                std::fs::DirBuilder::new()
                    .mode(0o700)
                    .create(&parent)
                    .map_err(|_| "创建共享子目录失败")?;
            }
            Err(_) => return Err("共享目录不可用".into()),
        }
    }
    Ok(parent.join(parts.last().unwrap()))
}
fn id(s: &str) -> Result<(), String> {
    if !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        Ok(())
    } else {
        Err("共享编号无效".into())
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Create {
    name: String,
    password: Option<String>,
    lifetime_minutes: u64,
}
pub async fn create(app: &App, args: Create) -> Result<Value, String> {
    if !segment(&args.name)
        || args
            .password
            .as_ref()
            .is_some_and(|s| s.len() > 128 || !s.is_ascii() || s.chars().any(char::is_control))
        || args.lifetime_minutes > 10080
    {
        return Err(
            "共享名称或有效期无效；原版共享密码 HTTP 头须使用 ASCII 字符，最多 128 字节".into(),
        );
    }
    let runtime = app.runtime.lock().await;
    if runtime.session.is_none() || app.chat.lock().await.get_chat_token().is_none() {
        return Err("请先加入大厅".into());
    }
    let mut folders = app.folders.lock().await;
    folders.collect();
    if folders.snapshots.len() >= 16 {
        return Err("最多保留 16 个共享快照".into());
    }
    let key = uuid::Uuid::new_v4().to_string();
    let root = std::env::temp_dir().join(format!("mctier-folder-{key}"));
    let mut builder = std::fs::DirBuilder::new();
    use std::os::unix::fs::DirBuilderExt;
    builder
        .mode(0o700)
        .create(&root)
        .map_err(|_| "创建共享快照失败")?;
    let expires = (args.lifetime_minutes > 0).then(|| now() + args.lifetime_minutes * 60);
    folders.snapshots.insert(
        key.clone(),
        Snapshot {
            root,
            files: 0,
            bytes: 0,
            name: args.name,
            password: args.password.filter(|s| !s.trim().is_empty()),
            expires,
            published: false,
            touched: std::time::Instant::now(),
        },
    );
    Ok(json!({"id":key}))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Upload {
    id: String,
    path: String,
}
pub async fn upload(
    State(app): State<Arc<App>>,
    Query(args): Query<Upload>,
    request: Request,
) -> Result<StatusCode, ApiError> {
    owner(&app, request.headers()).await?;
    id(&args.id)?;
    relative(&args.path, false)?;
    let _slot = app
        .folder_transfers
        .try_acquire()
        .map_err(|_| ApiError(StatusCode::TOO_MANY_REQUESTS, "已有两个文件正在传输".into()))?;
    let generation = *app.chat_generation.borrow();
    if app.chat.lock().await.get_chat_token().is_none() {
        return Err("聊天会话未就绪".to_string().into());
    }
    let mut changed = app.chat_generation.subscribe();
    if *changed.borrow() != generation {
        return Err("大厅会话已变更".to_string().into());
    }
    let bytes = tokio::select! {
        result=tokio::time::timeout(Duration::from_secs(120),to_bytes(request.into_body(),MAX_FILE)) => result.map_err(|_|"上传超时".to_string())?.map_err(|_|"单个文件不得超过 64 MiB".to_string())?,
        _=changed.changed()=>return Err("大厅会话已变更，请重新选择目录".to_string().into()),
    };
    let mut folders = app.folders.lock().await;
    if *app.chat_generation.borrow() != generation {
        return Err("大厅会话已变更，请重新选择目录".to_string().into());
    }
    folders.collect();
    if folders.bytes().saturating_add(bytes.len() as u64) > MAX_TOTAL
        || folders.count() >= MAX_FILES
    {
        return Err("共享总量超过 256 MiB 或 1024 个文件".to_string().into());
    }
    let snapshot = folders
        .snapshots
        .get_mut(&args.id)
        .ok_or("共享上传已失效".to_string())?;
    if snapshot.published {
        return Err("已发布快照不能修改，请重新选择目录".to_string().into());
    }
    let path = destination(&snapshot.root, &args.path)?;
    // Only server-created UUID roots exist here; path segments are strict and
    // never come from absolute OS paths. Reject existing files and symlinks.
    let mut file = tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&path)
        .await
        .map_err(|_| "文件重名或路径冲突".to_string())?;
    if file.write_all(&bytes).await.is_err() {
        let _ = tokio::fs::remove_file(path).await;
        return Err("写入共享文件失败".to_string().into());
    }
    snapshot.files += 1;
    snapshot.bytes += bytes.len() as u64;
    snapshot.touched = std::time::Instant::now();
    Ok(StatusCode::NO_CONTENT)
}
pub async fn publish(app: &App, key: String) -> Result<Value, String> {
    id(&key)?;
    let runtime = app.runtime.lock().await;
    let session = runtime.session.as_ref().ok_or("本地大厅未就绪")?;
    let chat = app.chat.lock().await;
    let token = chat.get_chat_token().ok_or("大厅凭据未就绪")?;
    let mut folders = app.folders.lock().await;
    folders.collect();
    let s = folders.snapshots.get(&key).ok_or("共享上传已失效")?;
    if s.files == 0 || s.published {
        return Err("目录为空或快照已发布".into());
    }
    let share: SharedFolder = serde_json::from_value(json!({"id":key,"name":s.name,"path":s.root,"password":s.password,"expire_time":s.expires,"compress_before_send":false,"owner_id":session.player_id,"created_at":now()})).map_err(|_|"创建共享信息失败")?;
    folders.server.set_virtual_ip(session.virtual_ip.clone());
    folders.server.set_lobby_token(token)?;
    if !folders.server.is_running() {
        folders
            .server
            .start_server()
            .await
            .map_err(|_| "不能在 EasyTier 虚拟接口上启动原版文件服务")?;
    }
    folders.server.add_share(share)?;
    folders.snapshots.get_mut(&key).unwrap().published = true;
    Ok(json!(folders.local()))
}
pub async fn remove(app: &App, key: String) -> Result<(), String> {
    id(&key)?;
    let mut folders = app.folders.lock().await;
    let _ = folders.server.remove_share(&key);
    folders.snapshots.remove(&key);
    if folders.snapshots.values().all(|s| !s.published) {
        folders.server.stop_server().await;
    }
    Ok(())
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Remote {
    player_id: String,
    share_id: Option<String>,
    path: Option<String>,
    password: Option<String>,
}
async fn remote(app: &App, args: &Remote, operation: &str) -> Result<reqwest::Response, String> {
    let chat = app.chat.lock().await;
    let peer = chat
        .peer_by_player_id(&args.player_id)
        .ok_or("该成员已离开大厅")?;
    let local = chat.get_local_identity().ok_or("本机聊天身份未就绪")?;
    let token = chat.get_chat_token().ok_or("大厅凭据未就绪")?;
    if args
        .password
        .as_ref()
        .is_some_and(|s| s.len() > 128 || !s.is_ascii() || s.chars().any(char::is_control))
    {
        return Err("原版共享密码 HTTP 头须使用 ASCII 字符，最多 128 字节".into());
    }
    let mut url = reqwest::Url::parse(&format!(
        "http://{}:14539/api/shares",
        chat_transport::chat_http_host(&peer.virtual_ip)?
    ))
    .map_err(|_| "虚拟地址无效")?;
    if operation != "shares" {
        let key = args.share_id.as_deref().ok_or("缺少共享编号")?;
        id(key)?;
        let path = args.path.as_deref().unwrap_or("");
        relative(path, operation == "files")?;
        {
            let mut segments = url.path_segments_mut().map_err(|_| "地址无效")?;
            segments.push(key).push(if operation == "files" {
                "files"
            } else {
                "download"
            });
            if operation == "download" {
                for part in path.split('/') {
                    segments.push(part);
                }
            }
        }
        if operation == "files" {
            url.query_pairs_mut().append_pair("path", path);
        }
    }
    drop(chat);
    let client = crate::overlay_http::client(Duration::from_secs(60), Duration::from_secs(4))
        .local_address(
            local
                .virtual_ip
                .parse::<std::net::IpAddr>()
                .map_err(|_| "本机虚拟地址无效")?,
        )
        .build()
        .map_err(|_| "创建共享客户端失败")?;
    let response = client
        .get(url)
        .header(LOBBY_TOKEN_HEADER, token)
        .header("x-share-password", args.password.as_deref().unwrap_or(""))
        .send()
        .await
        .map_err(|_| "无法连接该成员的文件共享服务，请检查 EasyTier 链路")?;
    if !response.status().is_success() {
        return Err(format!(
            "文件共享返回 HTTP {}（401 请核对共享密码或大厅凭据；404/410 共享可能已撤销）",
            response.status().as_u16()
        ));
    }
    Ok(response)
}
async fn bounded(mut response: reqwest::Response, limit: usize) -> Result<Vec<u8>, String> {
    if response.content_length().is_some_and(|n| n > limit as u64) {
        return Err("对端文件或目录列表超过浏览器版限制".into());
    }
    let mut data = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| "共享传输中断")? {
        if data.len().saturating_add(chunk.len()) > limit {
            return Err("对端文件或目录列表超过浏览器版限制".into());
        }
        data.extend_from_slice(&chunk);
    }
    Ok(data)
}
pub async fn list(app: &App, args: Remote, operation: &str) -> Result<Value, String> {
    let mut changed = app.chat_generation.subscribe();
    let request = async {
        let bytes = bounded(remote(app, &args, operation).await?, 512 * 1024).await?;
        serde_json::from_slice(&bytes).map_err(|_| "对端共享列表格式无效".into())
    };
    tokio::select! { result=request=>result, _=changed.changed()=>Err("大厅凭据已变更，请重新刷新共享列表".into()) }
}
pub async fn download(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Json(args): Json<Remote>,
) -> Result<Response, ApiError> {
    owner(&app, &headers).await?;
    let _slot = app
        .folder_transfers
        .clone()
        .try_acquire_owned()
        .map_err(|_| ApiError(StatusCode::TOO_MANY_REQUESTS, "已有两个文件正在传输".into()))?;
    let mut changed = app.chat_generation.subscribe();
    let request = async { bounded(remote(&app, &args, "download").await?, MAX_FILE).await };
    let data = tokio::select! { result=request=>result?, _=changed.changed()=>return Err("大厅凭据已变更，请重试下载".to_string().into()) };
    Ok(crate::binary_response::download(data, _slot))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn snapshot_paths_are_relative() {
        for path in [
            "../a",
            "a/../b",
            "/etc/passwd",
            "a\\b",
            "a//b",
            "a/./b",
            "C:/x",
            "a/\nsecret",
        ] {
            assert!(relative(path, false).is_err(), "{path}");
        }
        assert!(relative("子目录/a.txt", false).is_ok());
        assert!(relative("", true).is_ok());
    }
    #[test]
    fn snapshots_remove_only_their_generated_root() {
        let root =
            std::env::temp_dir().join(format!("mctier-folder-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("x"), "data").unwrap();
        drop(Snapshot {
            root: root.clone(),
            files: 1,
            bytes: 4,
            name: "x".into(),
            password: None,
            expires: None,
            published: false,
            touched: std::time::Instant::now(),
        });
        assert!(!root.exists());
    }
    #[test]
    fn identifiers_cannot_change_peer_url() {
        for key in ["..", "/etc", "a?x", "a#x", "http://host"] {
            assert!(id(key).is_err());
        }
        assert!(id("share-123").is_ok());
    }
}

#[cfg(test)]
mod api_tests {
    use super::*;
    use axum::{body::Body, http::Request as HttpRequest};
    use tower::ServiceExt;
    fn request(app: &App, url: &str, body: Body) -> HttpRequest<Body> {
        HttpRequest::builder()
            .method("POST")
            .uri(url)
            .header("host", "127.0.0.1:14700")
            .header("origin", ORIGIN)
            .header("x-mctier-csrf", &app.csrf)
            .header("x-mctier-client", "a".repeat(32))
            .body(body)
            .unwrap()
    }
    async fn fixture() -> (Arc<App>, String, PathBuf) {
        let app = App::new(PathBuf::from("/missing-test-core"));
        let chat = app.chat.lock().await;
        chat.set_virtual_ip("10.126.126.2".into());
        let (identity, _) = chat.signaling_identity().unwrap();
        chat.set_session(
            "a".repeat(64),
            1,
            identity.clone(),
            "Local".into(),
            Some(identity),
            vec![],
        )
        .unwrap();
        drop(chat);
        let key = uuid::Uuid::new_v4().to_string();
        let root = std::env::temp_dir().join(format!("mctier-folder-test-{key}"));
        std::fs::create_dir(&root).unwrap();
        app.folders.lock().await.snapshots.insert(
            key.clone(),
            Snapshot {
                root: root.clone(),
                files: 0,
                bytes: 0,
                name: "Selected".into(),
                password: None,
                expires: None,
                published: false,
                touched: std::time::Instant::now(),
            },
        );
        (app, key, root)
    }
    #[tokio::test]
    async fn folder_upload_enforces_boundary_paths_duplicate_names_private_permissions_and_cleanup()
    {
        let (app, key, root) = fixture().await;
        let url = format!("/api/folders/upload?id={key}&path=dir%2Ffile.txt");
        let mut req = request(&app, &url, Body::from("hello"));
        req.headers_mut().remove("x-mctier-csrf");
        assert_eq!(
            router(app.clone()).oneshot(req).await.unwrap().status(),
            StatusCode::FORBIDDEN
        );
        assert!(!root.join("dir").exists());
        let response = router(app.clone())
            .oneshot(request(&app, &url, Body::from("hello")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NO_CONTENT);
        assert_eq!(std::fs::read(root.join("dir/file.txt")).unwrap(), b"hello");
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(root.join("dir/file.txt"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        assert_eq!(
            std::fs::metadata(root.join("dir"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o700
        );
        assert_eq!(
            router(app.clone())
                .oneshot(request(&app, &url, Body::from("changed")))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
        assert_eq!(std::fs::read(root.join("dir/file.txt")).unwrap(), b"hello");
        let bad = format!("/api/folders/upload?id={key}&path=..%2Foutside");
        assert_eq!(
            router(app.clone())
                .oneshot(request(&app, &bad, Body::from("bad")))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
        remove(&app, key).await.unwrap();
        assert!(!root.exists());
    }
    #[tokio::test]
    async fn folder_upload_rejects_symlinks_published_snapshots_and_session_changes() {
        let (app, key, root) = fixture().await;
        let outside = tempfile::tempdir().unwrap();
        std::os::unix::fs::symlink(outside.path(), root.join("link")).unwrap();
        let url = format!("/api/folders/upload?id={key}&path=link%2Fsecret");
        assert_eq!(
            router(app.clone())
                .oneshot(request(&app, &url, Body::from("bad")))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
        assert!(!outside.path().join("secret").exists());
        app.folders
            .lock()
            .await
            .snapshots
            .get_mut(&key)
            .unwrap()
            .published = true;
        let url = format!("/api/folders/upload?id={key}&path=file");
        assert_eq!(
            router(app.clone())
                .oneshot(request(&app, &url, Body::from("bad")))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
        app.folders
            .lock()
            .await
            .snapshots
            .get_mut(&key)
            .unwrap()
            .published = false;
        let changed = app.clone();
        let stream = async_stream::stream! {changed.chat_generation.send_modify(|v|*v+=1);yield Ok::<_,std::io::Error>(bytes::Bytes::from_static(b"bad"));};
        let body = Body::from_stream(stream);
        assert_eq!(
            router(app.clone())
                .oneshot(request(&app, &url, body))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
        assert!(!root.join("file").exists());
        // A session change must also interrupt an upload that never completes,
        // releasing its slot immediately so the new room can transfer files.
        let ready = Arc::new(tokio::sync::Notify::new());
        let started = ready.clone();
        let pending_body = async_stream::stream! {
            started.notify_one();
            yield Ok::<_,std::io::Error>(std::future::pending::<bytes::Bytes>().await);
        };
        let request = request(&app, &url, Body::from_stream(pending_body));
        let service = router(app.clone());
        let task = tokio::spawn(async move { service.oneshot(request).await.unwrap() });
        tokio::time::timeout(Duration::from_secs(1), ready.notified())
            .await
            .unwrap();
        app.chat_generation.send_modify(|value| *value += 1);
        let response = tokio::time::timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        assert_eq!(app.folder_transfers.available_permits(), 2);
        app.leave().await;
        assert!(!root.exists());
    }
    #[tokio::test]
    async fn folder_budget_expiry_and_local_summary_do_not_expose_paths_or_passwords() {
        let (app, key, root) = fixture().await;
        app.folders
            .lock()
            .await
            .snapshots
            .get_mut(&key)
            .unwrap()
            .bytes = MAX_TOTAL;
        let url = format!("/api/folders/upload?id={key}&path=file");
        assert_eq!(
            router(app.clone())
                .oneshot(request(&app, &url, Body::from("x")))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
        {
            let mut folders = app.folders.lock().await;
            let snapshot = folders.snapshots.get_mut(&key).unwrap();
            snapshot.bytes = 0;
            snapshot.files = MAX_FILES;
        }
        assert_eq!(
            router(app.clone())
                .oneshot(request(&app, &url, Body::from("x")))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
        let share:SharedFolder=serde_json::from_value(json!({"id":key,"name":"Safe name","path":root,"password":"private","expire_time":null,"owner_id":"local","created_at":now()})).unwrap();
        app.folders.lock().await.server.add_share(share).unwrap();
        let result = serde_json::to_value(app.folders.lock().await.local()).unwrap();
        assert!(result[0].get("path").is_none());
        assert!(result[0].get("password").is_none());
        assert_eq!(result[0]["has_password"], true);
        app.folders
            .lock()
            .await
            .snapshots
            .get_mut(&key)
            .unwrap()
            .expires = Some(now() - 1);
        app.folders.lock().await.collect();
        assert!(!root.exists());
        app.leave().await;
    }
    #[tokio::test]
    async fn folder_proxy_rejects_unknown_members_and_unrecognized_request_fields_without_network()
    {
        let (app, _, _) = fixture().await;
        let args = Remote {
            player_id: "unknown".into(),
            share_id: Some("safe".into()),
            path: Some("file.txt".into()),
            password: None,
        };
        assert!(list(&app, args, "files")
            .await
            .unwrap_err()
            .contains("离开大厅"));
        assert!(serde_json::from_value::<Remote>(
            json!({"playerId":"known","peerIp":"127.0.0.1","path":"/etc/passwd"})
        )
        .is_err());
        let mut req = request(
            &app,
            "/api/folders/download",
            Body::from(r#"{"playerId":"unknown","shareId":"safe","path":"file.txt"}"#),
        );
        req.headers_mut().insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static("application/json"),
        );
        assert_eq!(
            router(app.clone()).oneshot(req).await.unwrap().status(),
            StatusCode::BAD_REQUEST
        );
        app.leave().await;
    }
}
