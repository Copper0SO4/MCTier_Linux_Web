//! Preview/confirm boundary for OS changes. No authorization from roster events.
use crate::{
    firewall,
    modules::{
        chat_service::ChatPeerIdentity,
    },
    runtime::App,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::BTreeSet,
    time::{Duration, Instant},
};
use tokio::sync::Mutex;

pub struct Operations {
    pending: Mutex<Option<Pending>>,
    gate: tokio::sync::Semaphore,
    scans: tokio::sync::Semaphore,
    cancellation: tokio::sync::watch::Sender<u64>,
}
struct Pending {
    token: String,
    created: Instant,
    action: Action,
}
enum Action {
    Firewall {
        plan: firewall::Plan,
        remove: bool,
        restart_network: bool,
        expected_session: Option<String>,
    },
}
impl Action {
    fn confirmation_text(&self) -> &'static str {
        match self {
            Action::Firewall {
                plan,
                remove: false,
                ..
            } if plan.pause => "我确认暂停整个防火墙",
            Action::Firewall { remove: false, .. } => "我确认修改本机防火墙",
            Action::Firewall { remove: true, .. } => "",
        }
    }
}
impl Operations {
    pub fn restart_guard(&self) -> Result<tokio::sync::SemaphorePermit<'_>, String> {
        self.gate
            .try_acquire()
            .map_err(|_| "系统操作仍在进行，请完成后再重新组网".into())
    }

    pub async fn cancel(&self) {
        self.cancellation.send_modify(|n| *n = n.wrapping_add(1));
        self.pending.lock().await.take();
    }
    pub fn new() -> Self {
        Self {
            pending: Mutex::new(None),
            gate: tokio::sync::Semaphore::new(1),
            scans: tokio::sync::Semaphore::new(1),
            cancellation: tokio::sync::watch::channel(0).0,
        }
    }
}
pub async fn identities(app: &App) -> Result<Vec<ChatPeerIdentity>, String> {
    let chat = app.chat.lock().await;
    let me = chat
        .get_local_identity()
        .ok_or("请先加入大厅并完成成员身份同步")?;
    let mut peers = chat.authoritative_peers();
    peers.push(me);
    peers.sort_by(|a, b| a.player_id.cmp(&b.player_id));
    peers.dedup_by(|a, b| a.player_id == b.player_id);
    if peers.len() > 254 {
        return Err("大厅成员超出虚拟网段容量".into());
    }
    Ok(peers)
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FirewallArgs {
    pub backend: String,
    pub zone: String,
    #[serde(default)]
    pub ephemeral_udp: bool,
    #[serde(default)]
    pub allow_outgoing: bool,
    #[serde(default)]
    pub remove_token: Option<String>,
    #[serde(default)]
    pub pause: bool,
    #[serde(default = "default_true")]
    pub restart_network: bool,
}
fn default_true() -> bool { true }
fn ledger_path() -> Result<std::path::PathBuf, String> {
    Ok(crate::modules::app_paths::data_root()
        .map_err(|e| e.to_string())?
        .join("linux-web/firewall-rules.json"))
}
fn read_ledger() -> Result<Vec<firewall::Plan>, String> {
    let path = ledger_path()?;
    read_ledger_at(&path)
}
fn read_ledger_at(path: &std::path::Path) -> Result<Vec<firewall::Plan>, String> {
    use std::io::Read;
    use std::os::unix::fs::OpenOptionsExt;
    let mut data = vec![];
    let file = match std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
    {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
        Err(_) => return Err("不能安全读取规则记录".into()),
    };
    if !file.metadata().map_err(|e| e.to_string())?.is_file() {
        return Err("规则记录不是普通文件".into());
    }
    file.take(32769)
        .read_to_end(&mut data)
        .map_err(|_| "不能读取规则记录")?;
    if data.len() > 32768 {
        return Err("规则记录超出限制".into());
    }
    let plans: Vec<firewall::Plan> =
        serde_json::from_slice(&data).map_err(|_| "规则记录无效，请自行检查防火墙")?;
    if plans.len() > 32 {
        return Err("规则记录过多，请先清理".into());
    }
    Ok(plans)
}
fn write_ledger(plans: &[firewall::Plan]) -> Result<(), String> {
    write_ledger_at(&ledger_path()?, plans)
}
fn write_ledger_at(path: &std::path::Path, plans: &[firewall::Plan]) -> Result<(), String> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let parent = path.parent().ok_or("规则记录路径无效")?;
    std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    if let Ok(metadata) = std::fs::symlink_metadata(path) {
        if !metadata.is_file() {
            return Err("拒绝覆盖非普通规则记录".into());
        }
    }
    let data = serde_json::to_vec(plans).map_err(|e| e.to_string())?;
    if plans.len() > 32 || data.len() > 32768 {
        return Err("规则记录超出限制".into());
    }
    let temp = parent.join(format!(".firewall-{}.tmp", uuid::Uuid::new_v4().simple()));
    let result = (|| {
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&temp)
            .map_err(|e| e.to_string())?;
        file.write_all(&data).map_err(|e| e.to_string())?;
        file.sync_all().map_err(|e| e.to_string())?;
        std::fs::rename(&temp, path).map_err(|e| e.to_string())?;
        std::fs::File::open(parent)
            .and_then(|dir| dir.sync_all())
            .map_err(|e| e.to_string())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&temp);
    }
    result
}
pub async fn firewall_status(app: &App) -> Result<Value, String> {
    let socket_status = {
        let runtime = app.runtime.lock().await;
        runtime.session.as_ref().map(
            |s| json!({
                "listenerPort":s.listener_port,
                "configuredListenerProtocols":if crate::runtime::uses_websocket_listener(&s.input.server_node) {vec!["tcp"]} else {vec!["udp", "tcp"]},
                "sockets":firewall_sockets(s.child.id())
            }),
        )
    };
    Ok(
        json!({"tools":firewall::detect().await,"savedRules":read_ledger()?,"currentCore":socket_status}),
    )
}
fn socket_ports(content: &str, owned: &BTreeSet<u64>) -> Vec<u16> {
    let mut result = BTreeSet::new();
    for row in content.lines().skip(1) {
        let cols = row.split_whitespace().collect::<Vec<_>>();
        if cols.len() < 10
            || !cols[9]
                .parse::<u64>()
                .ok()
                .is_some_and(|inode| owned.contains(&inode))
        {
            continue;
        }
        if let Some(port) = cols[1]
            .rsplit(':')
            .next()
            .and_then(|p| u16::from_str_radix(p, 16).ok())
        {
            if port != 0 {
                result.insert(port);
            }
        }
    }
    result.into_iter().collect()
}
fn firewall_sockets(pid: Option<u32>) -> Value {
    use std::io::Read;
    let result = (|| -> Result<Value, String> {
        let pid = pid.ok_or("核心进程已退出")?;
        let mut owned = BTreeSet::new();
        for entry in std::fs::read_dir(format!("/proc/{pid}/fd"))
            .map_err(|_| "无法读取核心 socket（capability 可能限制访问）")?
        {
            let entry = entry.map_err(|_| "核心 socket 列表变化，请刷新")?;
            if let Ok(link) = std::fs::read_link(entry.path()) {
                if let Some(inode) = link
                    .to_str()
                    .and_then(|s| s.strip_prefix("socket:["))
                    .and_then(|s| s.strip_suffix(']'))
                    .and_then(|s| s.parse::<u64>().ok())
                {
                    owned.insert(inode);
                }
            }
        }
        if owned.is_empty() {
            return Err("没有可读的核心 socket；端口可能随打洞变化，请刷新".into());
        }
        let mut udp = BTreeSet::new();
        let mut tcp = BTreeSet::new();
        for table in ["udp", "udp6", "tcp", "tcp6"] {
            let mut content = String::new();
            std::fs::File::open(format!("/proc/{pid}/net/{table}"))
                .map_err(|_| "无法读取核心 socket 状态")?
                .take(262145)
                .read_to_string(&mut content)
                .map_err(|_| "socket 表编码无效")?;
            if content.len() > 262144 {
                return Err("socket 表超出诊断上限".into());
            }
            let ports = socket_ports(&content, &owned);
            if table.starts_with("udp") {
                udp.extend(ports);
            } else {
                tcp.extend(ports);
            }
        }
        Ok(json!({"udp":udp,"tcp":tcp}))
    })();
    result.unwrap_or_else(|error| json!({"error":error}))
}
pub async fn prepare_firewall(app: &App, args: FirewallArgs) -> Result<Value, String> {
    let _guard = app
        .network_operations
        .gate
        .try_acquire()
        .map_err(|_| "已有系统授权进行中")?;
    let remove = args.remove_token.is_some();
    let detected = if !remove && args.backend == "firewalld" {
        Some(firewall::detect().await)
    } else {
        None
    };
    let rt = app.runtime.lock().await;
    let expected_session = rt.session.as_ref().map(|s| s.instance_id.clone());
    let plan = if let Some(token) = args.remove_token {
        read_ledger()?
            .into_iter()
            .find(|p| p.token == token)
            .ok_or("没有本次规则记录，不能撤销")?
    } else if args.pause {
        if read_ledger()?
            .iter()
            .any(|p| p.pause && p.backend == args.backend)
        {
            return Err("请先恢复该后端之前的暂停操作".into());
        }
        firewall::Plan {
            backend: args.backend,
            zone: String::new(),
            port: 0,
            protocol: String::new(),
            tcp_listener: false,
            overlay_zone: String::new(),
            quic_port: None,
            virtual_ip: String::new(),
            token: uuid::Uuid::new_v4().simple().to_string(),
            ephemeral_udp: None,
            ephemeral_tcp: None,
            pause: true,
            allow_outgoing: false,
        }
    } else {
        let s = rt
            .session
            .as_ref()
            .ok_or("请先连接 EasyTier，再按本次实际端口修复")?;
        if rt.status.lock().await.state != "interface-ready" {
            return Err("EasyTier 尚未就绪".into());
        }
        firewall::Plan {
            backend: args.backend,
            zone: args.zone,
            overlay_zone: detected
                .as_ref()
                .and_then(|v| v["overlayZone"].as_str())
                .unwrap_or("")
                .into(),
            port: s.listener_port,
            protocol: if crate::runtime::uses_websocket_listener(&s.input.server_node) {
                "tcp"
            } else {
                "udp"
            }
            .into(),
            tcp_listener: !crate::runtime::uses_websocket_listener(&s.input.server_node),
            quic_port: s
                .input
                .network_settings
                .quic
                .then_some(s.input.network_settings.quic_port),
            virtual_ip: s.virtual_ip.clone(),
            token: uuid::Uuid::new_v4().simple().to_string(),
            pause: false,
            allow_outgoing: args.allow_outgoing,
            ephemeral_tcp: if args.ephemeral_udp
                && (s.input.network_settings.tcp_hole_punching || args.allow_outgoing)
            {
                Some(firewall::ephemeral_range().ok_or("不能读取安全的动态 TCP 范围")?)
            } else {
                None
            },
            ephemeral_udp: if args.ephemeral_udp {
                Some(firewall::ephemeral_range().ok_or("不能读取安全的动态 UDP 范围")?)
            } else {
                None
            },
        }
    };
    drop(rt);
    plan.validate()?;
    let token = uuid::Uuid::new_v4().simple().to_string();
    let preview = json!({"token":token,"title":if remove{"撤销本次防火墙规则"}else{"修复本次 EasyTier 防火墙"},"lines":plan.descriptions(),"commands":plan.commands(remove),"confirmationText":if remove { "" } else if plan.pause { "我确认暂停整个防火墙" } else { "我确认修改本机防火墙" }});
    *app.network_operations.pending.lock().await = Some(Pending {
        token,
        created: Instant::now(),
        action: Action::Firewall {
            plan,
            remove,
            restart_network: args.restart_network,
            expected_session,
        },
    });
    Ok(preview)
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Confirm {
    pub token: String,
    pub confirmation_text: String,
}
pub async fn apply(app: &App, args: Confirm) -> Result<Value, String> {
    let _guard = app
        .network_operations
        .gate
        .try_acquire()
        .map_err(|_| "已有系统授权进行中")?;
    let mut cancel = app.network_operations.cancellation.subscribe();
    let mut lock = app.network_operations.pending.lock().await;
    let p = lock.as_ref().ok_or("请先预览操作")?;
    if p.token != args.token || p.created.elapsed() > Duration::from_secs(120) {
        return Err("预览失效，请重新检查和确认".into());
    }
    if args.confirmation_text != p.action.confirmation_text() {
        return Err(format!(
            "请完整输入“{}”后再申请系统授权",
            p.action.confirmation_text()
        ));
    }
    let p = lock.take().unwrap();
    drop(lock);
    match p.action {
        Action::Firewall { plan, remove, restart_network, expected_session } => {
            if !remove && !plan.pause {
                let rt = app.runtime.lock().await;
                let s = rt.session.as_ref().ok_or("大厅已经退出，请重新预览")?;
                if expected_session.as_deref() != Some(s.instance_id.as_str()) || s.listener_port != plan.port || s.virtual_ip != plan.virtual_ip {
                    return Err("EasyTier 实例已变化，请重新预览".into());
                }
            }
            // Save before applying so a partial failure remains reversible.
            let mut plans = read_ledger()?;
            if !remove && !plans.iter().any(|p| p.token == plan.token) {
                if plans.len() >= 32 {
                    return Err("请先撤销过往规则".into());
                }
                plans.push(plan.clone());
                write_ledger(&plans)?;
            }
            let helper = firewall::authorize(&mut cancel).await?;
            if !remove && !plan.pause {
                let rt = app.runtime.lock().await;
                let session = rt.session.as_ref().ok_or("认证期间已退出大厅")?;
                if expected_session.as_deref() != Some(session.instance_id.as_str()) || session.listener_port != plan.port || session.virtual_ip != plan.virtual_ip {
                    return Err("认证期间 EasyTier 实例已变化，请重新预览".into());
                }
            }
            let mut report = firewall::apply(helper, plan.clone(), remove, &mut cancel).await?;
            if remove {
                plans.retain(|p| p.token != plan.token);
                write_ledger(&plans)?;
            }

            let mut restart = json!({"state":"not-requested"});
            if restart_network {
                restart = match expected_session.as_deref() {
                    None => json!({"state":"skipped","reason":"预览时没有活动大厅，未重启任何新会话"}),
                    Some(expected) => match app.restart_network_for(Some(expected), None, &_guard).await {
                        Ok(_) => {
                            report.push("EasyTier 已在后台重新连接；请核对对端路由与媒体恢复。".into());
                            json!({"state":"restarted"})
                        },
                        Err(error) => {
                            report.push(format!("防火墙已变更，但原会话重连未完成：{error}。请检查本地会话，必要时重新加入。"));
                            json!({"state":"failed","error":error})
                        },
                    },
                };
            }
            Ok(json!({"report":report,"restart":restart}))
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Scan {
    pub port: u16,
}
pub async fn scan_mc(app: &App, args: Scan) -> Result<Value, String> {
    let _scan = app
        .network_operations
        .scans
        .try_acquire()
        .map_err(|_| "已有游戏查询进行中")?;
    let generation = *app.chat_generation.borrow();
    if !crate::network_settings::safe_port(args.port) {
        return Err("游戏端口无效或属于 MCTier 保留端口".into());
    }
    let peers = identities(app).await?;
    let ips = peers.iter().map(|p| p.virtual_ip.clone()).collect();
    let servers =
        crate::modules::minecraft_discovery::scan_minecraft_servers(ips, Some(args.port)).await;
    if *app.chat_generation.borrow() != generation || identities(app).await? != peers {
        return Err("大厅成员变化，发现结果已丢弃".into());
    }
    Ok(json!(servers))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn typed_confirmation_is_required_before_consuming_preview_or_authorizing() {
        let app = App::new(std::path::PathBuf::from("/missing-test-core"));
        *app.network_operations.pending.lock().await = Some(Pending {
            token: "preview".into(),
            created: Instant::now(),
            action: Action::Firewall {
                plan: serde_json::from_value(json!({
                    "backend":"ufw", "zone":"", "port":31111, "protocol":"udp",
                    "overlayZone":"", "quicPort":null, "virtualIp":"10.126.126.2",
                    "token":"a".repeat(32), "ephemeralUdp":null
                })).unwrap(),
                remove: false, restart_network: true, expected_session: None,
            },
        });
        let error = apply(
            &app,
            Confirm {
                token: "preview".into(),
                confirmation_text: "我确认".into(),
            },
        )
        .await
        .unwrap_err();
        assert!(error.contains("我确认修改本机防火墙"));
        assert!(app.network_operations.pending.lock().await.is_some());
        let error = apply(
            &app,
            Confirm {
                token: "preview".into(),
                confirmation_text: "我确认修改本机防火墙".into(),
            },
        )
        .await
        .unwrap_err();
        assert!(error.contains("大厅已经退出"));
        assert!(app.network_operations.pending.lock().await.is_none());
    }
    #[test]
    fn socket_diagnostics_are_filtered_by_owned_inode_and_return_only_ports() {
        let input = "header\n0: 0100007F:9DD4 00000000:0000 07 0:0 00:0 0 1000 0 123\n1: 0100007F:FFFF 00000000:0000 07 0:0 00:0 0 1000 0 456\n";
        assert_eq!(socket_ports(input, &BTreeSet::from([123])), vec![40404]);
    }
    #[test]
    fn ledger_atomic_private_and_symlink_safe() {
        use std::os::unix::fs::{symlink, PermissionsExt};
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("rules.json");
        write_ledger_at(&path, &[]).unwrap();
        assert!(read_ledger_at(&path).unwrap().is_empty());
        assert_eq!(
            std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let link = dir.path().join("link");
        symlink(&path, &link).unwrap();
        assert!(read_ledger_at(&link).is_err());
        assert!(write_ledger_at(&link, &[]).is_err());
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "[]");
    }
}
