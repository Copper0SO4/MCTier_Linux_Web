use crate::modules::{
    chat_service::ChatService,
    config_manager::{EasyTierAdvancedConfig, UserConfig},
    lobby_address, network_arguments,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeSet,
    path::PathBuf,
    process::Stdio,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::{Child, Command},
    sync::{watch, Mutex},
    task::JoinHandle,
};

const CORE_HASH: &str = "f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322";
pub const LEASE_TTL: Duration = Duration::from_secs(120);

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LobbyInput {
    pub name: String,
    pub password: String,
    pub player_name: String,
    pub server_node: String,
    pub signaling_server: String,
    #[serde(default)]
    pub address_attempt: u16,
    #[serde(default)]
    pub network_settings: crate::network_settings::NetworkSettings,
}

pub struct Session {
    pub instance_id: String,
    pub input: LobbyInput,
    pub player_id: String,
    pub virtual_ip: String,
    pub rpc_port: u16,
    pub listener_port: u16,
    pub(crate) child: Child,
    readers: Vec<JoinHandle<()>>,
    config_dir: PathBuf,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkStatus {
    pub state: String,
    pub virtual_ip: Option<String>,
    pub pid: Option<u32>,
    pub failure: Option<String>,
}

#[derive(Clone)]
pub struct ControlTicket { owner: String, generation: u64 }
pub struct Runtime {
    control_generation: u64,
    pub owner: Option<String>,
    pub last_lease: Instant,
    pub session: Option<Session>,
    pub status: Arc<Mutex<NetworkStatus>>,
}

pub struct App {
    pub csrf: String,
    pub runtime: Mutex<Runtime>,
    pub chat: Arc<Mutex<ChatService>>,
    pub chat_generation: watch::Sender<u64>,
    pub upload_budget: Mutex<(u64, u32)>,
    pub folders: Mutex<crate::folders::Folders>,
    pub folder_transfers: Arc<tokio::sync::Semaphore>,
    pub core: PathBuf,
    pub network_operations: crate::network_operations::Operations,
}

impl Runtime {
    pub fn ensure_control(&self, ticket: &ControlTicket) -> Result<(), String> {
        if self.owner.as_deref() != Some(ticket.owner.as_str()) || self.control_generation != ticket.generation {
            return Err("浏览器控制会话已改变，已拒绝过期请求".into());
        }
        Ok(())
    }
}

impl App {
    pub fn new(core: PathBuf) -> Arc<Self> {
        let (chat_generation, _) = watch::channel(0);
        Arc::new(Self {
            csrf: format!(
                "{}{}",
                uuid::Uuid::new_v4().simple(),
                uuid::Uuid::new_v4().simple()
            ),
            runtime: Mutex::new(Runtime {
                owner: None,
                control_generation: 0,
                last_lease: Instant::now(),
                session: None,
                status: Arc::new(Mutex::new(NetworkStatus {
                    state: "stopped".into(),
                    virtual_ip: None,
                    pid: None,
                    failure: None,
                })),
            }),
            chat: Arc::new(Mutex::new(ChatService::new())),
            chat_generation,
            upload_budget: Mutex::new((0, 0)),
            folders: Mutex::new(crate::folders::Folders::new()),
            folder_transfers: Arc::new(tokio::sync::Semaphore::new(2)),
            core,
            network_operations: crate::network_operations::Operations::new(),
        })
    }

    #[cfg(test)]
    pub async fn claim(&self, owner: &str) -> Result<(), String> {
        self.claim_ticket(owner).await.map(|_| ())
    }
    pub async fn claim_ticket(&self, owner: &str) -> Result<ControlTicket, String> {
        if owner.len() != 32 || !owner.bytes().all(|c| c.is_ascii_hexdigit()) {
            return Err("浏览器会话标识无效".into());
        }
        let mut runtime = self.runtime.lock().await;
        if runtime
            .owner
            .as_deref()
            .is_some_and(|active| active != owner)
        {
            return Err("另一个浏览器页面正在控制本地服务，请先在该页面退出".into());
        }
        if runtime.owner.is_none() { runtime.control_generation = runtime.control_generation.wrapping_add(1); }
        runtime.owner = Some(owner.into());
        runtime.last_lease = Instant::now();
        Ok(ControlTicket { owner: owner.into(), generation: runtime.control_generation })
    }

    pub async fn leave_owned(&self, ticket: &ControlTicket) -> Result<(), String> {
        let mut runtime = self.runtime.lock().await;
        runtime.ensure_control(ticket)?;
        self.stop_runtime(&mut runtime).await;
        Ok(())
    }
    pub async fn leave(&self) {
        let mut runtime = self.runtime.lock().await;
        self.stop_runtime(&mut runtime).await;
    }

    async fn stop_runtime(&self, runtime: &mut Runtime) {
        self.network_operations.cancel().await;
        self.chat_generation
            .send_modify(|generation| *generation = generation.wrapping_add(1));
        let chat = self.chat.lock().await;
        chat.stop_server().await;
        *self.upload_budget.lock().await = (0, 0);
        self.folders.lock().await.clear().await;
        chat.clear_session();
        chat.clear_local_messages();
        drop(chat);
        if let Some(mut session) = runtime.session.take() {
            for reader in session.readers {
                reader.abort();
            }
            let _ = session.child.kill().await;
            let _ = session.child.wait().await;
            // This UUID directory is created exclusively by this session.
            let _ = tokio::fs::remove_dir_all(session.config_dir).await;
        }
        *runtime.status.lock().await = NetworkStatus {
            state: "stopped".into(),
            virtual_ip: None,
            pid: None,
            failure: None,
        };
        runtime.owner = None;
        runtime.control_generation = runtime.control_generation.wrapping_add(1);
    }

    pub async fn maintenance(&self) {
        self.folders.lock().await.collect();
        let mut runtime = self.runtime.lock().await;
        let stale = runtime.owner.is_some() && runtime.last_lease.elapsed() > LEASE_TTL;
        let dead = runtime
            .session
            .as_mut()
            .is_some_and(|session| session.child.try_wait().ok().flatten().is_some());
        if stale || dead {
            self.stop_runtime(&mut runtime).await;
        }
    }

    pub async fn status(&self) -> Value {
        let runtime = self.runtime.lock().await;
        let network = runtime.status.lock().await.clone();
        let session = runtime.session.as_ref().map(|s| json!({"name":s.input.name,"playerId":s.player_id,"playerName":s.input.player_name,"serverNode":s.input.server_node,"signalingServer":s.input.signaling_server}));
        let chat = self.chat.lock().await;
        let identity = chat.get_local_identity();
        let received = chat
            .get_local_messages(None)
            .iter()
            .filter(|m| {
                identity
                    .as_ref()
                    .is_some_and(|me| m.player_id != me.player_id)
            })
            .count();
        json!({"service":"ready","network":network,"session":session,"chatRunning":chat.is_running(),"chatReceive":{"storedRemoteMessages":received,"authenticatedPeers":chat.authoritative_peers().len()},"webrtc":"browser-controlled","screenShare":"browser-controlled-unverified","remoteInput":"not-implemented"})
    }

    pub async fn start(&self, input: LobbyInput, ticket: &ControlTicket) -> Result<Value, String> {
        validate_input(&input)?;
        let mut runtime = self.runtime.lock().await;
        runtime.ensure_control(ticket)?;
        if runtime.session.is_some() {
            return Err("大厅正在连接或已连接，请先退出".into());
        }
        verify_core(&self.core).await?;
        let (player_id, _) = self.chat.lock().await.signaling_identity()?;
        let advanced = input.network_settings.config()?;
        let (config, automatic) = lobby_address::configuration(
            Some(&advanced),
            None,
            &input.name,
            &player_id,
            input.address_attempt,
        )?;
        let virtual_ip = config
            .ipv4
            .as_ref()
            .unwrap()
            .trim_end_matches("/24")
            .to_string();
        if std::net::TcpListener::bind((virtual_ip.as_str(), 0)).is_ok() {
            return Err("候选虚拟地址已被本机其它接口占用，请更换地址或退出其它网络实例".into());
        }
        let rpc_listener =
            std::net::TcpListener::bind("127.0.0.1:0").map_err(|_| "不能分配本地 RPC 端口")?;
        let rpc_port = rpc_listener
            .local_addr()
            .map_err(|_| "不能读取 RPC 端口")?
            .port();
        let ws = uses_websocket_listener(&input.server_node);
        let quic_reservation = if input.network_settings.quic {
            Some(
                std::net::UdpSocket::bind(("0.0.0.0", input.network_settings.quic_port))
                    .map_err(|_| "QUIC UDP 端口已占用")?,
            )
        } else {
            None
        };
        let (tcp_listener, udp_listener) =
            reserve_listeners(ws, input.network_settings.listener_port)?;
        let listener_port = tcp_listener
            .local_addr()
            .map_err(|_| "不能读取 EasyTier 端口")?
            .port();
        let dir = crate::modules::app_paths::data_root()
            .map_err(|_| "不能定位用户数据目录")?
            .join("linux-web")
            .join(format!("core-{}", uuid::Uuid::new_v4().simple()));
        tokio::fs::create_dir_all(&dir)
            .await
            .map_err(|_| "不能创建本实例的配置目录")?;
        let mut cmd = build_command(
            &self.core,
            &dir,
            &input,
            &player_id,
            rpc_port,
            listener_port,
            &config,
        );
        cmd.kill_on_drop(true)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        drop(rpc_listener);
        drop(udp_listener);
        drop(tcp_listener);
        drop(quic_reservation);
        let mut child = match cmd.spawn() {
            Ok(child) => child,
            Err(_) => {
                let _ = tokio::fs::remove_dir_all(&dir).await;
                return Err("无法以当前用户启动已校验的 EasyTier 核心".into());
            }
        };
        let status = runtime.status.clone();
        *status.lock().await = NetworkStatus {
            state: "starting".into(),
            virtual_ip: None,
            pid: child.id(),
            failure: None,
        };
        let readers = core_readers(&mut child, status.clone());
        runtime.session = Some(Session {
            instance_id: uuid::Uuid::new_v4().to_string(),
            input: input.clone(),
            player_id: player_id.clone(),
            virtual_ip: virtual_ip.clone(),
            rpc_port,
            child,
            listener_port,
            readers,
            config_dir: dir,
        });
        // A configured IP in stdout is insufficient: bind only once the kernel
        // really owns the address. No room membership/media success is inferred.
        let deadline = Instant::now() + Duration::from_secs(15);
        let ready = loop {
            let session = runtime.session.as_mut().unwrap();
            match session.child.try_wait() {
                Ok(Some(_)) => break Err("EasyTier 启动后已退出".to_string()),
                Err(_) => break Err("不能检查 EasyTier 进程".to_string()),
                Ok(None) => {}
            }
            if let Some(failure) = status.lock().await.failure.clone() {
                break Err(failure);
            }
            match std::net::TcpListener::bind((virtual_ip.as_str(), 0)) {
                Ok(_) => break Ok(()),
                Err(_) if Instant::now() < deadline => {
                    tokio::time::sleep(Duration::from_millis(150)).await
                }
                Err(_) => break Err("等待 EasyTier 虚拟接口地址就绪超时，尚未进入大厅".into()),
            }
        };
        if let Err(error) = ready {
            // Keep the session lock until failed-start cleanup completes: a new
            // room must not be stopped by this older request's cleanup.
            self.stop_runtime(&mut runtime).await;
            return Err(error);
        }
        self.chat.lock().await.set_virtual_ip(virtual_ip.clone());
        status.lock().await.state = "interface-ready".into();
        status.lock().await.virtual_ip = Some(virtual_ip.clone());
        runtime.last_lease = Instant::now();
        Ok(
            json!({"name":input.name,"virtual_ip":virtual_ip,"automatic_virtual_ip":automatic,"is_host":false,"player_id":player_id,"server_node":input.server_node,"signaling_server":input.signaling_server}),
        )
    }

    /// Restart only the owned core. Keep the room identity, chat and firewall port stable.
    #[cfg(test)]
    pub async fn restart_network(&self) -> Result<Value, String> {
        let _guard = self.network_operations.restart_guard()?;
        self.restart_network_for(None, None, &_guard).await
    }

    pub async fn restart_network_owned(&self, ticket: &ControlTicket) -> Result<Value, String> {
        let guard = self.network_operations.restart_guard()?;
        self.restart_network_for(None, Some(ticket), &guard).await
    }

    // The caller retains the operation permit across validation and restart.
    // Room changes may proceed to cancel authorization, but cannot redirect it.
    pub(crate) async fn restart_network_for(
        &self,
        expected: Option<&str>,
        control: Option<&ControlTicket>,
        _guard: &tokio::sync::SemaphorePermit<'_>,
    ) -> Result<Value, String> {
        let mut runtime = self.runtime.lock().await;
        if let Some(ticket) = control { runtime.ensure_control(ticket)?; }
        let session = runtime.session.as_ref().ok_or("请先加入大厅")?;
        if expected.is_some_and(|id| id != session.instance_id) {
            return Err("大厅会话已改变，未重启新会话的 EasyTier".into());
        }
        self.network_operations.cancel().await;
        let status = runtime.status.clone();
        let session = runtime.session.as_mut().unwrap();
        verify_core(&self.core).await?;
        let (config, _) = lobby_address::configuration(
            Some(&session.input.network_settings.config()?),
            None,
            &session.input.name,
            &session.player_id,
            session.input.address_attempt,
        )?;
        if config.ipv4.as_deref().map(|ip| ip.trim_end_matches("/24"))
            != Some(session.virtual_ip.as_str())
        {
            return Err("虚拟地址与当前会话不一致，拒绝重新组网".into());
        }
        let mut cmd = build_command(
            &self.core,
            &session.config_dir,
            &session.input,
            &session.player_id,
            session.rpc_port,
            session.listener_port,
            &config,
        );
        cmd.kill_on_drop(true)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        session
            .child
            .kill()
            .await
            .map_err(|_| "不能停止当前 EasyTier，未启动第二个实例")?;
        session
            .child
            .wait()
            .await
            .map_err(|_| "不能确认当前 EasyTier 已退出")?;
        for reader in session.readers.drain(..) {
            reader.abort();
        }
        let result = match reserve_listeners(
            uses_websocket_listener(&session.input.server_node),
            session.listener_port,
        ) {
            Ok(reservations) => {
                drop(reservations);
                launch_replacement(session, status, cmd).await
            }
            Err(error) => Err(error),
        };
        if result.is_err() {
            self.stop_runtime(&mut runtime).await;
        } else {
            runtime.last_lease = Instant::now();
        }
        result
    }

    pub async fn virtual_ip(&self) -> Option<String> {
        self.runtime
            .lock()
            .await
            .session
            .as_ref()
            .map(|s| s.virtual_ip.clone())
    }

    pub async fn rpc(&self) -> Result<Value, String> {
        let runtime = self.runtime.lock().await;
        let s = runtime.session.as_ref().ok_or("EasyTier 尚未启动")?;
        let cli = self.core.with_file_name("easytier-cli");
        verify_digest(
            &cli,
            "e339aea31943f0c5ced2a5a6ecdd675da3bb25843cf847107744e656f8200838",
        )
        .await?;
        let output = tokio::time::timeout(
            Duration::from_secs(4),
            Command::new(cli)
                .args(["--rpc-portal", &format!("127.0.0.1:{}", s.rpc_port), "peer"])
                .kill_on_drop(true)
                .output(),
        )
        .await
        .map_err(|_| "EasyTier peer 查询超时")?
        .map_err(|_| "EasyTier peer 查询失败")?;
        if !output.status.success() {
            return Err("EasyTier RPC 尚不可用".into());
        }
        if output.stdout.len() > 64 * 1024 {
            return Err("EasyTier RPC 结果超限".into());
        }
        Ok(json!({"peers":String::from_utf8_lossy(&output.stdout)}))
    }
}

async fn launch_replacement(
    session: &mut Session,
    status: Arc<Mutex<NetworkStatus>>,
    mut cmd: Command,
) -> Result<Value, String> {
    session.child = cmd
        .spawn()
        .map_err(|_| "重新启动 EasyTier 失败".to_string())?;
    *status.lock().await = NetworkStatus {
        state: "starting".into(),
        virtual_ip: None,
        pid: session.child.id(),
        failure: None,
    };
    session.readers = core_readers(&mut session.child, status.clone());
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        if session
            .child
            .try_wait()
            .map_err(|_| "不能检查 EasyTier 进程")?
            .is_some()
        {
            return Err("重新启动的 EasyTier 已退出".to_string());
        }
        if let Some(error) = status.lock().await.failure.clone() {
            return Err(error);
        }
        if std::net::TcpListener::bind((session.virtual_ip.as_str(), 0)).is_ok() {
            break;
        }
        if Instant::now() >= deadline {
            return Err("重新组网等待虚拟接口超时".to_string());
        }
        tokio::time::sleep(Duration::from_millis(150)).await;
    }
    let mut state = status.lock().await;
    state.state = "interface-ready".into();
    state.virtual_ip = Some(session.virtual_ip.clone());
    Ok(json!({"virtualIp":session.virtual_ip,"listenerPort":session.listener_port}))
}

fn core_readers(child: &mut Child, status: Arc<Mutex<NetworkStatus>>) -> Vec<JoinHandle<()>> {
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    // Read bounded lines without recording arguments, passwords or tokens.
    let mut readers = Vec::new();
    for stream in [
        Box::pin(stdout) as std::pin::Pin<Box<dyn tokio::io::AsyncRead + Send>>,
        Box::pin(stderr),
    ] {
        let status = status.clone();
        readers.push(tokio::spawn(async move {
            let mut reader = BufReader::new(stream);
            let mut line = Vec::new();
            loop {
                line.clear();
                use tokio::io::AsyncReadExt;
                let mut bounded = (&mut reader).take(16 * 1024);
                match bounded.read_until(b'\n', &mut line).await {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {
                        let text = String::from_utf8_lossy(&line).to_ascii_lowercase();
                        if text.contains("tun device error")
                            || text.contains("operation not permitted")
                            || text.contains("failed to create tun")
                        {
                            status.lock().await.failure = Some(
                                "EasyTier 报告虚拟网卡创建失败，请核对 TUN 设备及 capability"
                                    .into(),
                            );
                        }
                    }
                }
            }
        }));
    }
    readers
}

pub fn core_path() -> PathBuf {
    let packaged = std::env::current_exe()
        .unwrap()
        .parent()
        .unwrap()
        .join("binaries/easytier-core");
    if packaged.is_file() {
        packaged
    } else {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../resources/binaries/easytier-core")
    }
}

pub fn capabilities_present(raw: &str) -> bool {
    let Some(caps) = raw.split_whitespace().last() else {
        return false;
    };
    let Some((names, flags)) = caps.split_once('=') else {
        return false;
    };
    let names: BTreeSet<_> = names.split(',').collect();
    names == BTreeSet::from(["cap_net_admin", "cap_net_raw"])
        && flags.contains('e')
        && flags.contains('p')
        && !flags.contains('i')
}

async fn verify_digest(path: &std::path::Path, expected: &str) -> Result<(), String> {
    if tokio::fs::symlink_metadata(path)
        .await
        .map_err(|_| "随包 EasyTier 核心/CLI 缺失，请先下载官方二进制")?
        .file_type()
        .is_symlink()
    {
        return Err("拒绝通过符号链接运行 EasyTier".into());
    }
    let data = tokio::fs::read(path)
        .await
        .map_err(|_| "不能读取随包 EasyTier")?;
    if format!("{:x}", Sha256::digest(data)) != expected {
        return Err("随包 EasyTier 哈希校验失败，拒绝运行".into());
    }
    Ok(())
}

async fn verify_core(path: &std::path::Path) -> Result<(), String> {
    if unsafe { libc::geteuid() } == 0 {
        return Err("本地服务与 EasyTier 必须由普通用户运行".into());
    }
    verify_digest(path, CORE_HASH).await?;
    let output = Command::new("/usr/sbin/getcap")
        .arg(path)
        .output()
        .await
        .map_err(|_| "缺少 getcap，请安装 libcap2-bin")?;
    if !output.status.success() || !capabilities_present(&String::from_utf8_lossy(&output.stdout)) {
        return Err("EasyTier 缺少 cap_net_admin,cap_net_raw=ep；请由用户确认后仅对核心执行 pkexec setcap 并复核".into());
    }
    if !std::path::Path::new("/dev/net/tun").exists() {
        return Err("系统缺少 /dev/net/tun".into());
    }
    Ok(())
}

pub fn validate_input(input: &LobbyInput) -> Result<(), String> {
    input.network_settings.config()?;
    let name = input.name.trim();
    if name != input.name
        || !(4..=32).contains(&name.chars().count())
        || !name.chars().any(char::is_alphanumeric)
        || !name
            .chars()
            .all(|c| c.is_alphanumeric() || "_- ".contains(c))
    {
        return Err("大厅名称须为 4–32 个中文、字母、数字、空格、下划线或连字符".into());
    }
    validate_password(&input.password)?;
    if input.player_name.trim().is_empty()
        || input.player_name.chars().count() > 32
        || input.player_name.chars().any(char::is_control)
    {
        return Err("玩家名称无效".into());
    }
    for (endpoint, signal) in [(&input.signaling_server, true), (&input.server_node, false)] {
        if endpoint.len() > 512
            || endpoint
                .chars()
                .any(|c| c.is_whitespace() || c.is_control() || c == '\\')
        {
            return Err("服务器地址格式无效".into());
        }
        let url = reqwest::Url::parse(endpoint).map_err(|_| "服务器地址格式无效")?;
        if url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.fragment().is_some()
            || if signal {
                url.scheme() != "wss"
            } else {
                !matches!(url.scheme(), "tcp" | "udp" | "ws" | "wss" | "quic" | "txt")
            }
        {
            return Err("必须提供明确的 EasyTier 节点与 wss 信令地址".into());
        }
    }
    Ok(())
}

pub fn defaults() -> Value {
    let config = UserConfig::default();
    json!({"serverNode":config.private_easytier_server,"signalingServer":config.private_signaling_server,"version":env!("MCTIER_UPSTREAM_VERSION"),"releaseVersion":env!("CARGO_PKG_VERSION")})
}

pub(crate) fn uses_websocket_listener(server_node: &str) -> bool {
    server_node.starts_with("ws://") || server_node.starts_with("wss://")
}

// Keep the stable TCP direct-connect path and UDP listener on the same port.
// Retry only automatic allocation; never silently replace an explicit port.
fn reserve_listeners(
    websocket: bool,
    port: u16,
) -> Result<(std::net::TcpListener, Option<std::net::UdpSocket>), String> {
    for _ in 0..32 {
        let tcp = std::net::TcpListener::bind(("0.0.0.0", port))
            .map_err(|_| "EasyTier TCP 监听端口无法绑定，请检查端口占用")?;
        if websocket {
            return Ok((tcp, None));
        }
        let allocated = tcp
            .local_addr()
            .map_err(|_| "不能读取 EasyTier TCP 端口")?
            .port();
        match std::net::UdpSocket::bind(("0.0.0.0", allocated)) {
            Ok(udp) => return Ok((tcp, Some(udp))),
            Err(error) if port == 0 && error.kind() == std::io::ErrorKind::AddrInUse => {}
            Err(_) => return Err("EasyTier UDP 监听端口无法绑定，请检查端口占用".into()),
        }
    }
    Err("不能分配同时可用的 EasyTier TCP/UDP 监听端口".into())
}

pub fn build_command(
    core: &std::path::Path,
    dir: &std::path::Path,
    input: &LobbyInput,
    player_id: &str,
    rpc: u16,
    listener: u16,
    config: &EasyTierAdvancedConfig,
) -> Command {
    let mut cmd = Command::new(core);
    // Explicit UI/configured endpoints are authoritative, even when launched
    // from a shell containing EasyTier ET_* environment overrides.
    for (key, _) in std::env::vars_os() {
        if key.to_string_lossy().starts_with("ET_") {
            cmd.env_remove(key);
        }
    }
    let ws = uses_websocket_listener(&input.server_node);
    let listeners = if ws {
        vec![format!("ws://0.0.0.0:{listener}/")]
    } else {
        vec![
            format!("udp://0.0.0.0:{listener}"),
            format!("tcp://0.0.0.0:{listener}"),
        ]
    };
    cmd.args([
        "--network-name",
        &format!("MCTier-{}", input.name),
        "--network-secret",
        &input.password,
        "--peers",
        &input.server_node,
        "--hostname",
        &format!("player-{}", &player_id[..12]),
        "--instance-name",
        &format!("mctier-web-{}", uuid::Uuid::new_v4().simple()),
        "--config-dir",
    ])
    .arg(dir)
    .args(["--rpc-portal", &format!("127.0.0.1:{rpc}")])
    .arg("--listeners")
    .args(listeners)
    .args(["--default-protocol", if ws { "ws" } else { "udp" }]);
    network_arguments::apply_advanced_config(&mut cmd, config);
    cmd
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample_input() -> LobbyInput {
        LobbyInput {
            name: "room-test".into(),
            password: "password123".into(),
            player_name: "Alice".into(),
            server_node: "tcp://node.example:11010".into(),
            signaling_server: "wss://signal.example/signaling".into(),
            address_attempt: 0,
            network_settings: Default::default(),
        }
    }
    #[test]
    fn reservations_require_both_protocols_and_fixed_port_conflicts_do_not_fallback() {
        let (tcp, udp) = reserve_listeners(false, 0).unwrap();
        let port = tcp.local_addr().unwrap().port();
        assert_eq!(udp.as_ref().unwrap().local_addr().unwrap().port(), port);
        assert!(reserve_listeners(false, port).unwrap_err().contains("TCP"));
        drop(tcp);
        // Parallel process tests can briefly retain a forked descriptor until
        // exec closes it. Wait for TCP release while retaining the UDP conflict.
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            let error = reserve_listeners(false, port).unwrap_err();
            if error.contains("UDP") {
                break;
            }
            assert!(
                error.contains("TCP") && Instant::now() < deadline,
                "{error}"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        // A failed UDP reservation must release the partially reserved TCP socket.
        let available_tcp = loop {
            match std::net::TcpListener::bind(("0.0.0.0", port)) {
                Ok(tcp) => break tcp,
                Err(error) => {
                    assert!(Instant::now() < deadline, "{error}");
                    std::thread::sleep(Duration::from_millis(10));
                }
            }
        };
        drop(available_tcp);
        drop(udp);

        let (ws_tcp, ws_udp) = reserve_listeners(true, 0).unwrap();
        assert!(ws_udp.is_none());
        assert_ne!(ws_tcp.local_addr().unwrap().port(), 0);
    }

    #[test]
    fn ordinary_nodes_get_tcp_udp_listeners_but_websocket_keeps_its_transport() {
        let mut input = sample_input();
        for (node, expected) in [
            (
                "tcp://node.example:11010",
                vec!["udp://0.0.0.0:31111", "tcp://0.0.0.0:31111"],
            ),
            (
                "udp://node.example:11010",
                vec!["udp://0.0.0.0:31111", "tcp://0.0.0.0:31111"],
            ),
            ("wss://node.example:11010/", vec!["ws://0.0.0.0:31111/"]),
        ] {
            input.server_node = node.into();
            let cmd = build_command(
                std::path::Path::new("core"),
                std::path::Path::new("private-instance"),
                &input,
                &"a".repeat(64),
                15889,
                31111,
                &input.network_settings.config().unwrap(),
            );
            let args = cmd
                .as_std()
                .get_args()
                .map(|s| s.to_string_lossy().into_owned())
                .collect::<Vec<_>>();
            let start = args.iter().position(|s| s == "--listeners").unwrap() + 1;
            let end = args.iter().position(|s| s == "--default-protocol").unwrap();
            assert_eq!(&args[start..end], expected.as_slice());
            assert_eq!(
                args[args.iter().position(|s| s == "--peers").unwrap() + 1],
                node
            );
            assert_eq!(
                args[args
                    .iter()
                    .position(|s| s == "--encryption-algorithm")
                    .unwrap()
                    + 1],
                "aes-256-gcm"
            );
        }
    }
    #[tokio::test]
    async fn restart_requires_an_active_session_and_exclusive_operation_guard() {
        let app = App::new(PathBuf::from("/unused-test-core"));
        assert!(app.restart_network().await.unwrap_err().contains("先加入"));
        let _permit = app.network_operations.restart_guard().unwrap();
        assert!(app
            .restart_network()
            .await
            .unwrap_err()
            .contains("系统操作"));
    }

    #[tokio::test]
    async fn old_controller_requests_are_rejected_even_when_tab_id_is_reused() {
        let app = App::new(PathBuf::from("/missing-core"));
        let id = "a".repeat(32);
        let old = app.claim_ticket(&id).await.unwrap();
        app.leave().await;
        let current = app.claim_ticket(&id).await.unwrap();
        assert!(app.leave_owned(&old).await.unwrap_err().contains("过期请求"));
        assert!(app.start(sample_input(), &old).await.unwrap_err().contains("过期请求"));
        assert!(app.restart_network_owned(&old).await.unwrap_err().contains("过期请求"));
        app.runtime.lock().await.ensure_control(&current).unwrap();
        app.leave_owned(&current).await.unwrap();
    }

    #[tokio::test]
    async fn restart_bound_to_old_instance_never_kills_new_room() {
        let app = App::new(PathBuf::from("/missing-core"));
        let dir = tempfile::tempdir().unwrap();
        let child = Command::new("/usr/bin/sleep").arg("60").kill_on_drop(true).spawn().unwrap();
        let pid = child.id().unwrap();
        app.runtime.lock().await.session = Some(Session {
            instance_id: "new-room-instance".into(), input: sample_input(), player_id: "a".repeat(64),
            virtual_ip: "10.126.126.2".into(), rpc_port: 15889, listener_port: 15890,
            child, readers: vec![], config_dir: dir.path().into(),
        });
        let guard = app.network_operations.restart_guard().unwrap();
        let error = app.restart_network_for(Some("old-room-instance"), None, &guard).await.unwrap_err();
        assert!(error.contains("会话已改变"));
        assert!(app.runtime.lock().await.session.as_mut().unwrap().child.try_wait().unwrap().is_none());
        assert_eq!(app.runtime.lock().await.session.as_ref().unwrap().child.id(), Some(pid));
        app.leave().await;
    }

    #[tokio::test]
    async fn replacement_exits_are_detected_without_claiming_network_success() {
        let dir = tempfile::tempdir().unwrap();
        let child = Command::new("/usr/bin/false")
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let mut session = Session {
            instance_id: uuid::Uuid::new_v4().to_string(),
            input: sample_input(),
            player_id: "a".repeat(64),
            virtual_ip: "not-an-ip".into(),
            rpc_port: 31000,
            listener_port: 31001,
            child,
            readers: vec![],
            config_dir: dir.path().into(),
        };
        session.child.wait().await.unwrap();
        let status = Arc::new(Mutex::new(NetworkStatus {
            state: "stopped".into(),
            virtual_ip: None,
            pid: None,
            failure: None,
        }));
        let mut cmd = Command::new("/usr/bin/false");
        cmd.kill_on_drop(true)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        let error = launch_replacement(&mut session, status.clone(), cmd)
            .await
            .unwrap_err();
        assert!(error.contains("已退出"));
        assert_ne!(status.lock().await.state, "interface-ready");
        assert_eq!(session.listener_port, 31001);
        assert_eq!(session.input.server_node, "tcp://node.example:11010");
        for reader in session.readers.drain(..) {
            reader.abort();
        }
        let error = launch_replacement(&mut session, status, Command::new("/does-not-exist-test"))
            .await
            .unwrap_err();
        assert!(error.contains("启动 EasyTier 失败"));
    }

    #[test]
    fn capabilities_require_both_effective_and_permitted_without_overgrant() {
        assert!(capabilities_present("/binary cap_net_admin,cap_net_raw=ep"));
        for raw in [
            "",
            "/binary cap_net_admin=ep",
            "/binary cap_net_raw=ep",
            "/binary cap_net_admin,cap_net_raw=p",
            "/binary cap_net_admin,cap_net_raw,cap_sys_admin=ep",
            "/binary cap_net_admin,cap_net_raw=eip",
        ] {
            assert!(!capabilities_present(raw), "{raw}");
        }
    }
    #[test]
    fn easytier_arguments_preserve_credentials_selected_node_and_fixed_subnet() {
        let input = sample_input();
        let (config, _) =
            lobby_address::configuration(None, None, &input.name, &"a".repeat(64), 0).unwrap();
        let command = build_command(
            std::path::Path::new("core"),
            std::path::Path::new("private-instance"),
            &input,
            &"a".repeat(64),
            15889,
            15890,
            &config,
        );
        let args: Vec<_> = command
            .as_std()
            .get_args()
            .map(|s| s.to_string_lossy().into_owned())
            .collect();
        for (flag, expected) in [
            ("--network-name", "MCTier-room-test"),
            ("--network-secret", "password123"),
            ("--peers", "tcp://node.example:11010"),
            ("--rpc-portal", "127.0.0.1:15889"),
            ("--dhcp", "false"),
        ] {
            assert_eq!(
                args[args.iter().position(|s| s == flag).unwrap() + 1],
                expected
            );
        }
        assert_eq!(args.iter().filter(|s| s.as_str() == "--peers").count(), 1);
        assert!(config.ipv4.unwrap().starts_with("10.126.126."));
        assert!(!args.contains(&"--no-tun".into()));
    }
    #[test]
    fn invalid_endpoints_and_credentials_are_rejected_without_fallback() {
        let mut input = sample_input();
        assert!(validate_input(&input).is_ok());
        for endpoint in [
            "ws://signal.example",
            "https://signal.example",
            "wss://user:pass@signal.example",
            "wss://signal.example/#fragment",
        ] {
            input.signaling_server = endpoint.into();
            assert!(validate_input(&input).is_err());
        }
        input = sample_input();
        input.server_node = "http://node.example".into();
        assert!(validate_input(&input).is_err());
        input = sample_input();
        input.password = "short".into();
        assert!(validate_input(&input).is_err());
        input = sample_input();
        input.name = "../etc/passwd".into();
        assert!(validate_input(&input).is_err());
    }
    #[test]
    fn advanced_settings_use_original_arguments_without_opening_local_control_ports() {
        let mut input = sample_input();
        input.network_settings = crate::network_settings::NetworkSettings {
            ipv4: "10.126.126.20".into(),
            listener_port: 31111,
            mtu: 1300,
            multi_thread: false,
            latency_first: false,
            kcp: true,
            quic: true,
            quic_port: 31112,
            p2p_mode: "relay".into(),
            ..Default::default()
        };
        let global = input.network_settings.config().unwrap();
        let (config, automatic) =
            lobby_address::configuration(Some(&global), None, &input.name, &"a".repeat(64), 0)
                .unwrap();
        assert!(!automatic);
        assert_eq!(config.ipv4.as_deref(), Some("10.126.126.20/24"));
        let cmd = build_command(
            std::path::Path::new("core"),
            std::path::Path::new("private-instance"),
            &input,
            &"a".repeat(64),
            15889,
            31111,
            &config,
        );
        let args = cmd
            .as_std()
            .get_args()
            .map(|s| s.to_string_lossy().into_owned())
            .collect::<Vec<_>>();
        for flag in ["--enable-kcp-proxy", "--enable-quic-proxy", "--disable-p2p"] {
            assert!(args.iter().any(|s| s == flag));
        }
        assert!(!args.iter().any(|s| s == "--multi-thread"
            || s == "--latency-first"
            || s == "--accept-dns"
            || s == "--disable-encryption"));
        assert_eq!(
            args[args.iter().position(|s| s == "--quic-listen-port").unwrap() + 1],
            "31112"
        );
        assert_eq!(
            args[args.iter().position(|s| s == "--rpc-portal").unwrap() + 1],
            "127.0.0.1:15889"
        );
    }
    #[tokio::test]
    async fn stop_terminates_only_its_owned_child_and_cleans_its_instance_directory() {
        let app = App::new(PathBuf::from("/unused-test-core"));
        let temp = tempfile::tempdir().unwrap();
        let owned = temp.path().join("core-owned");
        tokio::fs::create_dir(&owned).await.unwrap();
        let mut unrelated = Command::new("/usr/bin/sleep")
            .arg("60")
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let child = Command::new("/usr/bin/sleep")
            .arg("60")
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let pid = child.id().unwrap();
        app.runtime.lock().await.session = Some(Session {
            instance_id: uuid::Uuid::new_v4().to_string(),
            input: sample_input(),
            player_id: "a".repeat(64),
            virtual_ip: "10.126.126.2".into(),
            rpc_port: 15889,
            listener_port: 15890,
            child,
            readers: vec![],
            config_dir: owned.clone(),
        });
        app.leave().await;
        assert!(app.runtime.lock().await.session.is_none());
        assert!(!owned.exists());
        assert!(unrelated.try_wait().unwrap().is_none());
        assert_eq!(unsafe { libc::kill(pid as i32, 0) }, -1);
        unrelated.kill().await.unwrap();
        unrelated.wait().await.unwrap();
    }
}

pub fn validate_password(value: &str) -> Result<(), String> {
    let password = value.trim();
    if value.chars().any(char::is_control) {
        return Err("密码包含控制字符".into());
    }
    if !password.is_empty()
        && (!(8..=32).contains(&password.len())
            || !password.chars().any(char::is_alphabetic)
            || !password.chars().any(char::is_numeric))
    {
        return Err("非空密码须为 8–32 字节，并含字母和数字".into());
    }
    Ok(())
}
