mod auto_join;
mod attachments;
mod overlay_http;
mod binary_response;
mod firewall;
mod firewall_pause;
mod folders;
#[allow(dead_code)]
mod modules;
mod network_operations;
mod network_settings;
mod privileged;
mod runtime;
mod version_metadata;

use axum::{
    extract::{DefaultBodyLimit, Path, Request, State},
    http::{header, HeaderMap, HeaderValue, Method, StatusCode},
    middleware::{self, Next},
    response::{
        sse::{Event, KeepAlive},
        IntoResponse, Response, Sse,
    },
    routing::{get, post},
    Json, Router,
};
use modules::{chat_service::ChatPeerIdentity, chat_transport};
use runtime::{App, LobbyInput};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{convert::Infallible, sync::Arc, time::Duration};

const ORIGIN: &str = "http://127.0.0.1:14700";
include!(concat!(env!("OUT_DIR"), "/assets.rs"));

#[derive(Debug)]
struct ApiError(StatusCode, String);
impl From<String> for ApiError {
    fn from(message: String) -> Self {
        Self(StatusCode::BAD_REQUEST, message)
    }
}
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"error":self.1}))).into_response()
    }
}
fn value<T: serde::de::DeserializeOwned>(input: Value) -> Result<T, ApiError> {
    serde_json::from_value(input)
        .map_err(|_| ApiError(StatusCode::BAD_REQUEST, "命令参数格式无效".into()))
}

async fn boundary(State(app): State<Arc<App>>, request: Request, next: Next) -> Response {
    let headers = request.headers();
    let host_ok =
        headers.get(header::HOST).and_then(|v| v.to_str().ok()) == Some("127.0.0.1:14700");
    let origin = headers.get(header::ORIGIN).and_then(|v| v.to_str().ok());
    let same_origin = origin.is_none_or(|v| v == ORIGIN);
    let site_ok = headers
        .get("sec-fetch-site")
        .and_then(|v| v.to_str().ok())
        .is_none_or(|v| matches!(v, "same-origin" | "none"));
    let mutation = request.method() != Method::GET && request.method() != Method::HEAD;
    let csrf_ok = !mutation
        || (origin == Some(ORIGIN)
            && headers.get("x-mctier-csrf").and_then(|v| v.to_str().ok())
                == Some(app.csrf.as_str()));
    if !host_ok || !same_origin || !site_ok || !csrf_ok {
        return StatusCode::FORBIDDEN.into_response();
    }
    let mut response = next.run(request).await;
    let h = response.headers_mut();
    h.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    h.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    h.insert(header::CONTENT_SECURITY_POLICY,HeaderValue::from_static("default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' wss:; media-src 'self' blob: data:; img-src 'self' blob: data:; worker-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"));
    h.insert(
        "permissions-policy",
        HeaderValue::from_static("microphone=(self), camera=(), display-capture=(self)"),
    );
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

fn asset(url: &str) -> Response {
    let url = if url == "/" { "/index.html" } else { url };
    let Some((_, bytes)) = ASSETS.iter().find(|(path, _)| *path == url) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let mime = if url.ends_with(".html") {
        "text/html; charset=utf-8"
    } else if url.ends_with(".js") {
        "text/javascript; charset=utf-8"
    } else if url.ends_with(".css") {
        "text/css; charset=utf-8"
    } else if url.ends_with(".svg") {
        "image/svg+xml"
    } else if url.ends_with(".gif") {
        "image/gif"
    } else if url.ends_with(".png") {
        "image/png"
    } else if url.ends_with(".mp3") {
        "audio/mpeg"
    } else {
        "application/octet-stream"
    };
    ([(header::CONTENT_TYPE, mime)], *bytes).into_response()
}
async fn index() -> Response {
    asset("/")
}
async fn assets(Path(path): Path<String>) -> Response {
    asset(&format!("/{path}"))
}
async fn bootstrap(State(app): State<Arc<App>>) -> Json<Value> {
    Json(json!({"csrf":app.csrf,"defaults":runtime::defaults()}))
}
async fn status(State(app): State<Arc<App>>) -> Json<Value> {
    Json(app.status().await)
}

async fn owner(app: &App, headers: &HeaderMap) -> Result<runtime::ControlTicket, ApiError> {
    let id = headers
        .get("x-mctier-client")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| ApiError(StatusCode::UNAUTHORIZED, "缺少浏览器会话标识".into()))?;
    app.claim_ticket(id)
        .await
        .map_err(|message| ApiError(StatusCode::CONFLICT, message))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Invoke {
    command: String,
    #[serde(default)]
    args: Value,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct SignArgs {
    challenge: String,
    lobby_name: String,
    virtual_ip: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ChatArgs {
    chat_token: String,
    chat_token_epoch: u64,
    player_id: String,
    player_name: String,
    host_id: Option<String>,
    peers: Vec<ChatPeerIdentity>,
    reset_auth_baseline: Option<bool>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Peers {
    peers: Vec<ChatPeerIdentity>,
    host_id: Option<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Send {
    player_id: String,
    player_name: String,
    content: String,
    message_type: String,
    image_data: Option<Vec<u8>>,
    message_id: Option<String>,
    recipient_id: Option<String>,
    #[serde(default)]
    peer_ips: Vec<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct History {
    #[serde(default)]
    peer_ips: Vec<String>,
    since: Option<u64>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Stop {
    preserve_signing_identity: Option<bool>,
}

async fn invoke(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Json(input): Json<Invoke>,
) -> Result<Json<Value>, ApiError> {
    let control = owner(&app, &headers).await?;
    let result = match input.command.as_str() {
        "get_auto_join_config" => auto_join::get()?,
        "save_auto_join_config" => auto_join::save(value(input.args)?)?,
        "adopt_auto_network" => app.adopt_auto_network(&control).await?,
        "detach_browser" => { app.detach_owned(&control).await?; Value::Null },
        "get_upstream_version_tags" => json!(version_metadata::fetch_tags().await?),
        "get_network_settings" => json!(network_settings::NetworkSettings::default()),
        "validate_network_settings" => {
            let settings = value::<network_settings::NetworkSettings>(input.args)?;
            settings.config()?;
            json!(settings)
        }
        "get_firewall_status" => network_operations::firewall_status(&app).await?,
        "prepare_firewall_repair" => {
            network_operations::prepare_firewall(&app, value(input.args)?).await?
        }
        "cancel_network_operation" => {
            app.network_operations.cancel().await;
            json!(true)
        }
        "apply_network_operation" => network_operations::apply(&app, value(input.args)?).await?,
        "scan_minecraft_servers" => network_operations::scan_mc(&app, value(input.args)?).await?,
        "connect_lobby" => {
            let mut args = value::<LobbyInput>(input.args)?;
            args.password = modules::secret_store::resolve(&args.password)?;
            app.start(args, &control).await?
        }
        "protect_lobby_password" | "export_lobby_password" | "resolve_lobby_password" => {
            #[derive(Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Password {
                password: String,
            }
            let args: Password = value(input.args)?;
            json!(match input.command.as_str() {
                "protect_lobby_password" =>
                    modules::secret_store::protect_lobby_password(args.password)?,
                "export_lobby_password" =>
                    modules::secret_store::export_lobby_password(args.password)?,
                _ => modules::secret_store::resolve(&args.password)?,
            })
        }
        "create_folder_snapshot" => folders::create(&app, value(input.args)?).await?,
        "publish_folder_snapshot" | "remove_shared_folder" => {
            #[derive(Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Id {
                id: String,
            }
            let args: Id = value(input.args)?;
            if input.command == "publish_folder_snapshot" {
                folders::publish(&app, args.id).await?
            } else {
                folders::remove(&app, args.id).await?;
                Value::Null
            }
        }
        "remote_folder_shares" | "remote_folder_files" => {
            folders::list(
                &app,
                value(input.args)?,
                if input.command == "remote_folder_shares" {
                    "shares"
                } else {
                    "files"
                },
            )
            .await?
        }
        "leave_lobby" | "force_stop_easytier" => {
            app.leave_owned(&control).await?;
            Value::Null
        }
        "restart_easytier_network" => app.restart_network_owned(&control).await?,
        "get_virtual_ip" => json!(app.virtual_ip().await),
        "get_config" => json!(modules::config_manager::UserConfig::default()),
        "get_settings" => json!({"language":"system"}),
        "prepare_signaling_identity" => {
            // Explicit browser join or opt-in startup profile uses the same signer.
            let (id, key) = app.chat.lock().await.signaling_identity()?;
            json!({"clientId":id,"identityPublicKey":key})
        }
        "sign_signaling_registration" => {
            let args: SignArgs = value(input.args)?;
            if args.challenge.len() != 64 || !args.challenge.bytes().all(|c| c.is_ascii_hexdigit())
            {
                return Err("信令 challenge 格式无效".to_string().into());
            }
            let runtime = app.runtime.lock().await;
            runtime.ensure_control(&control)?;
            let session = runtime
                .session
                .as_ref()
                .ok_or_else(|| "本地大厅会话未就绪".to_string())?;
            if args.lobby_name != session.input.name || args.virtual_ip != session.virtual_ip {
                return Err("拒绝签署其它大厅或虚拟 IP 的 challenge".to_string().into());
            }
            let (id, key, sig) = app.chat.lock().await.sign_signaling_registration(
                &args.challenge,
                &args.lobby_name,
                &args.virtual_ip,
            )?;
            json!({"clientId":id,"identityPublicKey":key,"challengeSignature":sig})
        }
        "configure_p2p_chat" => {
            let args: ChatArgs = value(input.args)?;
            if args.peers.len() > chat_transport::MAX_CHAT_TARGETS {
                return Err("大厅成员超过限制".to_string().into());
            }
            let runtime = app.runtime.lock().await;
            runtime.ensure_control(&control)?;
            let session = runtime
                .session
                .as_ref()
                .ok_or_else(|| "本地大厅会话未就绪".to_string())?;
            if args.player_id != session.player_id || args.player_name != session.input.player_name
            {
                return Err("聊天身份与本地会话不一致".to_string().into());
            }
            let chat = app.chat.lock().await;
            let credential_changed =
                chat.get_chat_token().as_deref() != Some(args.chat_token.as_str());
            if args.reset_auth_baseline == Some(true) {
                chat.reset_auth_baseline().await;
                app.folders.lock().await.server.clear_lobby_token();
                app.chat_generation.send_modify(|v| *v = v.wrapping_add(1));
            }
            chat.set_session(
                args.chat_token.clone(),
                args.chat_token_epoch,
                args.player_id,
                args.player_name,
                args.host_id,
                args.peers,
            )?;
            app.folders
                .lock()
                .await
                .server
                .set_lobby_token(args.chat_token)?;
            chat.start_server()
                .await
                .map_err(|_| "不能绑定 EasyTier 虚拟 IP 上的认证聊天服务".to_string())?;
            if credential_changed && args.reset_auth_baseline != Some(true) {
                app.chat_generation.send_modify(|v| *v = v.wrapping_add(1));
            }
            Value::Null
        }
        "update_p2p_chat_peers" => {
            let args: Peers = value(input.args)?;
            if args.peers.len() > chat_transport::MAX_CHAT_TARGETS {
                return Err("大厅成员超过限制".to_string().into());
            }
            app.chat
                .lock()
                .await
                .update_peer_identities(args.peers, args.host_id)?;
            Value::Null
        }
        "stop_p2p_chat" => {
            let args: Stop = value(input.args)?;
            let runtime = app.runtime.lock().await;
            runtime.ensure_control(&control)?;
            let chat = app.chat.lock().await;
            app.chat_generation.send_modify(|v| *v = v.wrapping_add(1));
            if args.preserve_signing_identity == Some(true) {
                chat.reset_auth_baseline().await;
                app.folders.lock().await.server.clear_lobby_token();
            } else {
                chat.stop_server().await;
                app.folders.lock().await.clear().await;
                *app.upload_budget.lock().await = (0, 0);
                chat.clear_session();
            }
            Value::Null
        }
        "prepare_chat_image" => {
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase", deny_unknown_fields)]
            struct ImageInput {
                image_data: String,
                recipient_id: Option<String>,
            }
            let args: ImageInput = value(input.args)?;
            let chat = app.chat.lock().await;
            if chat.get_chat_token().is_none() {
                return Err("聊天会话未就绪".to_string().into());
            }
            if args
                .recipient_id
                .as_deref()
                .is_some_and(|id| chat.peer_by_player_id(id).is_none())
            {
                return Err("收件人已离线".to_string().into());
            }
            attachments::prepare_image(&args.image_data)?
        }
        "send_p2p_chat_message" => {
            let args: Send = value(input.args)?;
            chat_transport::send_p2p_chat_message(
                args.player_id,
                args.player_name,
                args.content,
                args.message_type,
                args.image_data,
                args.message_id,
                args.recipient_id,
                args.peer_ips,
                app.chat.clone(),
            )
            .await?
        }
        "get_p2p_chat_messages" => {
            let args: History = value(input.args)?;
            json!(
                chat_transport::get_p2p_chat_messages(args.peer_ips, args.since, app.chat.clone())
                    .await?
            )
        }
        "get_local_p2p_chat_messages" => {
            #[derive(Deserialize)]
            #[serde(deny_unknown_fields)]
            struct LocalHistory {
                since: Option<u64>,
            }
            let args: LocalHistory = value(input.args)?;
            let chat = app.chat.lock().await;
            if chat.get_chat_token().is_none() {
                return Err("聊天会话未就绪".to_string().into());
            }
            json!(chat.get_local_messages(args.since))
        }
        "clear_p2p_chat_messages" => {
            app.chat.lock().await.clear_local_messages();
            Value::Null
        }
        "get_easytier_peers" => app.rpc().await?,
        // On Linux, the upstream voice ICE command returns no discovery server.
        // Retain that behavior; actual browser ICE pairs must be measured.
        "voice_ice_server" => Value::Null,
        "native_microphone_supported" => json!(false),
        "send_heartbeat" => Value::Null, // Browser signaling already sends its own authenticated ping.
        "report_audio_diagnostic" => {
            // Diagnostics are presented locally without logging supplied text,
            // which could otherwise contain SDP/credentials or arbitrary paths.
            Value::Null
        }
        "stop_file_server" => {
            app.folders.lock().await.clear().await;
            Value::Null
        }
        "get_local_shares" => json!(app.folders.lock().await.local()),
        _ => {
            return Err(ApiError(
                StatusCode::NOT_IMPLEMENTED,
                format!(
                    "浏览器服务暂不支持此功能：{}",
                    input.command.chars().take(64).collect::<String>()
                ),
            ))
        }
    };
    Ok(Json(result))
}

async fn lease(State(app): State<Arc<App>>, headers: HeaderMap) -> Result<StatusCode, ApiError> {
    owner(&app, &headers).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn chat_stream(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    // GET streaming must carry the boot nonce too; SSE URLs contain no token.
    if headers.get("x-mctier-csrf").and_then(|v| v.to_str().ok()) != Some(app.csrf.as_str()) {
        return Err(ApiError(StatusCode::FORBIDDEN, "消息流缺少本地认证".into()));
    }
    owner(&app, &headers).await?;
    let mut generation = app.chat_generation.subscribe();
    let mut receiver = {
        let chat = app.chat.lock().await;
        let token = chat
            .get_chat_token()
            .ok_or_else(|| "聊天会话未就绪".to_string())?;
        if headers
            .get("x-mctier-chat-token")
            .and_then(|v| v.to_str().ok())
            != Some(token.as_str())
        {
            return Err(ApiError(StatusCode::UNAUTHORIZED, "聊天令牌已失效".into()));
        }
        chat.subscribe_local_messages()
    };
    let stream = async_stream::stream! {
        // Flush the authenticated response immediately; silence must not look
        // like an unestablished fetch until the first 15-second keepalive.
        yield Ok::<Event,Infallible>(Event::default().comment("ready"));
        loop {
            tokio::select! {
                _=generation.changed()=>break,
                message=receiver.recv()=>match message {
                    Ok(message)=>{if let Ok(data)=serde_json::to_string(&message) {yield Ok::<Event,Infallible>(Event::default().data(data));}},
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_))=>break, // Client recovers via bounded history.
                    Err(_)=>break,
                }
            }
        }
    };
    Ok(Sse::new(stream)
        .keep_alive(
            KeepAlive::new()
                .interval(Duration::from_secs(15))
                .text("keep-alive"),
        )
        .into_response())
}

fn router(app: Arc<App>) -> Router {
    Router::new()
        .route("/", get(index))
        .route("/healthz", get(|| async { "ok" }))
        .route("/api/bootstrap", get(bootstrap))
        .route("/api/status", get(status))
        .route("/api/invoke", post(invoke))
        .route("/api/lease", post(lease))
        .route("/api/chat/stream", get(chat_stream))
        .route("/api/chat/attachment", post(attachments::download))
        .route("/api/chat/upload", post(attachments::upload))
        .route("/api/folders/upload", post(folders::upload))
        .route("/api/folders/download", post(folders::download))
        .route("/*path", get(assets))
        .layer(DefaultBodyLimit::max(16 * 1024 * 1024))
        .layer(tower::limit::ConcurrencyLimitLayer::new(16))
        .layer(middleware::from_fn_with_state(app.clone(), boundary))
        .with_state(app)
}

fn main() {
    firewall::run_if_requested();
    run_service();
}
#[tokio::main]
async fn run_service() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("warn")).init();
    if unsafe { libc::geteuid() } == 0 {
        eprintln!("Run the local service as a normal user.");
        std::process::exit(1);
    }
    let app = App::new(runtime::core_path());
    let listener = match tokio::net::TcpListener::bind("127.0.0.1:14700").await {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("Cannot listen on 127.0.0.1:14700: {error}");
            std::process::exit(1);
        }
    };
    println!("MCTier Linux local service ready. Open http://127.0.0.1:14700 manually in Firefox or Chromium. Startup networking is used only when explicitly enabled in settings.");
    let startup = tokio::spawn(auto_join::startup(app.clone()));
    let watchdog = app.clone();
    let worker = tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(5)).await;
            watchdog.maintenance().await;
        }
    });
    let shutdown = app.clone();
    let result = axum::serve(listener, router(app.clone()))
        .with_graceful_shutdown(async move {
            #[cfg(unix)]
            {
                let mut terminate =
                    tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                        .unwrap();
                tokio::select! {_=tokio::signal::ctrl_c()=>{},_=terminate.recv()=>{}}
            }
            #[cfg(not(unix))]
            let _ = tokio::signal::ctrl_c().await;
            shutdown.leave().await;
        })
        .await;
    startup.abort();
    worker.abort();
    app.leave().await;
    if let Err(error) = result {
        eprintln!("Local service failed: {error}");
    }
}

#[cfg(test)]
mod tests;
