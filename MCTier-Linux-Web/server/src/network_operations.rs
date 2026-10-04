//! Preview/confirm boundary for OS changes. No authorization from roster events.
use crate::{
    firewall,
    modules::{
        chat_service::ChatPeerIdentity,
        hosts_manager::HostsManager,
        hosts_security::{validate_hosts_update, MAX_HOSTS_BYTES},
        virtual_network::virtual_host,
    },
    runtime::App,
};
use serde::{Deserialize, Serialize};
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
    Dns {
        old: String,
        new: String,
        identities: Option<Vec<ChatPeerIdentity>>,
    },
    Firewall {
        plan: firewall::Plan,
        remove: bool,
    },
}
impl Operations {
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
const MARKER: &str = "# MCTier Magic DNS - LinuxWeb";
const END: &str = "# MCTier Magic DNS End";

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
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Mapping {
    player_name: String,
    domain: String,
    ip: String,
}
fn mappings(peers: &[ChatPeerIdentity]) -> Result<Vec<Mapping>, String> {
    let mut domains = BTreeSet::new();
    let mut ips = BTreeSet::new();
    let mut out = vec![];
    for p in peers {
        if virtual_host(&p.virtual_ip).is_none() {
            return Err("成员虚拟地址无效，拒绝写入 hosts".into());
        }
        let domain = HostsManager::domain_for_identity(&p.player_id).map_err(|e| e.to_string())?;
        if !domains.insert(domain.clone()) || !ips.insert(p.virtual_ip.clone()) {
            return Err("成员域名或虚拟地址冲突".into());
        }
        out.push(Mapping {
            player_name: p.player_name.clone(),
            domain,
            ip: p.virtual_ip.clone(),
        });
    }
    Ok(out)
}
fn hosts_content(old: &str, mappings: &[Mapping]) -> Result<String, String> {
    validate_hosts_update(old, old)?;
    // Keep every unrelated byte/section; only this adapter's section is replaced.
    let mut inside = false;
    let mut new = String::new();
    for line in old.split_inclusive('\n') {
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed == MARKER {
            inside = true;
            continue;
        }
        if inside && trimmed == END {
            inside = false;
            continue;
        }
        if !inside {
            new.push_str(line);
        }
    }
    for line in new.lines() {
        let fields = line
            .split('#')
            .next()
            .unwrap_or("")
            .split_whitespace()
            .collect::<Vec<_>>();
        if fields.len() > 1
            && mappings.iter().any(|m| {
                fields[1..]
                    .iter()
                    .any(|domain| domain.eq_ignore_ascii_case(&m.domain))
                    && fields[0] != m.ip
            })
        {
            return Err("已有其它 hosts 记录与大厅域名冲突；请自行检查，本功能不会覆盖它".into());
        }
    }
    if !mappings.is_empty() {
        if !new.is_empty() && !new.ends_with('\n') {
            new.push('\n');
        }
        new.push_str(MARKER);
        new.push('\n');
        for m in mappings {
            new.push_str(&format!("{}\t{}\n", m.ip, m.domain));
        }
        new.push_str(END);
        new.push('\n');
    }
    validate_hosts_update(old, &new)?;
    Ok(new)
}
fn read_hosts() -> Result<String, String> {
    use std::io::Read;
    let mut s = String::new();
    std::fs::File::open("/etc/hosts")
        .map_err(|_| "不能读取系统 hosts")?
        .take(MAX_HOSTS_BYTES as u64 + 1)
        .read_to_string(&mut s)
        .map_err(|_| "系统 hosts 编码无效")?;
    if s.len() > MAX_HOSTS_BYTES {
        return Err("系统 hosts 超出安全限制".into());
    }
    Ok(s)
}
pub async fn dns_status(app: &App) -> Value {
    let old = read_hosts();
    let peers = identities(app).await;
    let entries = peers
        .as_ref()
        .ok()
        .and_then(|p| mappings(p).ok())
        .unwrap_or_default();
    let installed = old.as_ref().is_ok_and(|s| s.lines().any(|l| l == MARKER));
    let up_to_date = old
        .as_ref()
        .is_ok_and(|s| hosts_content(s, &entries).is_ok_and(|updated| updated == *s));
    json!({"entries":entries,"installed":installed,"upToDate":up_to_date,"error":old.err().or_else(||peers.err())})
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DnsArgs {
    pub remove: bool,
}
pub async fn prepare_dns(app: &App, args: DnsArgs) -> Result<Value, String> {
    let _guard = app
        .network_operations
        .gate
        .try_acquire()
        .map_err(|_| "已有系统授权进行中")?;
    let peers = if args.remove {
        None
    } else {
        Some(identities(app).await?)
    };
    let entries = peers
        .as_ref()
        .map(|p| mappings(p))
        .transpose()?
        .unwrap_or_default();
    let old = read_hosts()?;
    let new = hosts_content(&old, &entries)?;
    if old == new {
        return Err(if args.remove {
            "没有 Linux Web hosts 记录需要清理".into()
        } else {
            "当前记录已一致，无需授权".into()
        });
    }
    let token = uuid::Uuid::new_v4().simple().to_string();
    let preview = json!({"token":token,"title":if args.remove{"清理 Linux Web 域名"}else{"更新 Magic DNS"},"lines":["仅修改 /etc/hosts 中 LinuxWeb 标记段，保留其它记录。","成员变更后需手动更新；退出大厅不会自动弹出授权或清理，请及时撤销。"],"entries":entries});
    *app.network_operations.pending.lock().await = Some(Pending {
        token,
        created: Instant::now(),
        action: Action::Dns {
            old,
            new,
            identities: peers,
        },
    });
    Ok(preview)
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FirewallArgs {
    pub backend: String,
    pub zone: String,
    #[serde(default)]
    pub ephemeral_udp: bool,
    #[serde(default)]
    pub remove_token: Option<String>,
}
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
pub async fn firewall_status() -> Result<Value, String> {
    Ok(json!({"tools":firewall::detect().await,"savedRules":read_ledger()?}))
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
    let plan = if let Some(token) = args.remove_token {
        read_ledger()?
            .into_iter()
            .find(|p| p.token == token)
            .ok_or("没有本次规则记录，不能撤销")?
    } else {
        let rt = app.runtime.lock().await;
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
            protocol: if s.input.server_node.starts_with("ws://")
                || s.input.server_node.starts_with("wss://")
            {
                "tcp"
            } else {
                "udp"
            }
            .into(),
            quic_port: s
                .input
                .network_settings
                .quic
                .then_some(s.input.network_settings.quic_port),
            virtual_ip: s.virtual_ip.clone(),
            token: uuid::Uuid::new_v4().simple().to_string(),
            ephemeral_udp: if args.ephemeral_udp {
                Some(firewall::ephemeral_range().ok_or("不能读取安全的动态 UDP 范围")?)
            } else {
                None
            },
        }
    };
    plan.validate()?;
    let token = uuid::Uuid::new_v4().simple().to_string();
    let preview = json!({"token":token,"title":if remove{"撤销本次防火墙规则"}else{"修复本次 EasyTier 防火墙"},"lines":plan.descriptions(),"commands":plan.commands(remove)});
    *app.network_operations.pending.lock().await = Some(Pending {
        token,
        created: Instant::now(),
        action: Action::Firewall { plan, remove },
    });
    Ok(preview)
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Confirm {
    pub token: String,
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
    let p = lock.take().unwrap();
    drop(lock);
    match p.action {
        Action::Dns {
            old,
            new,
            identities: expected,
        } => {
            if let Some(ref expected) = expected {
                if &identities(app).await? != expected {
                    return Err("成员列表已改变，请重新预览域名映射".into());
                }
            }
            if read_hosts()? != old {
                return Err("hosts 已改变，请重新预览".into());
            }
            let helper =
                crate::privileged::Helper::start(crate::privileged::HOSTS_SWITCH, &mut cancel)
                    .await?;
            // Authentication can outlive the preview's original room/hosts snapshot.
            if let Some(ref expected) = expected {
                if &identities(app).await? != expected {
                    return Err("认证期间成员已变化，请重新预览".into());
                }
            }
            if read_hosts()? != old {
                return Err("认证期间 hosts 已变化，请重新预览".into());
            }
            use sha2::{Digest, Sha256};
            helper.execute(json!({"expected_sha256":format!("{:x}", Sha256::digest(old.as_bytes())), "content":new}), &mut cancel).await?;
            if read_hosts()? != new {
                return Err("hosts 写入后复核失败".into());
            }
            Ok(json!({"report":["hosts 标记段已写入并复核；域名实际解析和游戏访问仍需测试。"]}))
        }
        Action::Firewall { plan, remove } => {
            if !remove {
                let rt = app.runtime.lock().await;
                let s = rt.session.as_ref().ok_or("大厅已经退出，请重新预览")?;
                if s.listener_port != plan.port || s.virtual_ip != plan.virtual_ip {
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
            if !remove {
                let rt = app.runtime.lock().await;
                let session = rt.session.as_ref().ok_or("认证期间已退出大厅")?;
                if session.listener_port != plan.port || session.virtual_ip != plan.virtual_ip {
                    return Err("认证期间 EasyTier 实例已变化，请重新预览".into());
                }
            }
            let report = firewall::apply(helper, plan.clone(), remove, &mut cancel).await?;
            if remove {
                plans.retain(|p| p.token != plan.token);
                write_ledger(&plans)?;
            }
            Ok(json!({"report":report}))
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
    #[test]
    fn hosts_updates_only_own_section() {
        let old="127.0.0.1 localhost\n# MCTier Magic DNS - other\n10.126.126.3 other.mct.net\n# MCTier Magic DNS End\n";
        let peer = ChatPeerIdentity {
            player_id: "a".repeat(64),
            player_name: "<script>".into(),
            virtual_ip: "10.126.126.2".into(),
            chat_public_key: None,
        };
        let map = mappings(&[peer.clone()]).unwrap();
        let new = hosts_content(old, &map).unwrap();
        assert!(new.starts_with(old));
        assert!(new.contains(&format!("{}.mct.net", "a".repeat(32))));
        assert_eq!(hosts_content(&new, &[]).unwrap(), old);
        assert!(mappings(&[peer.clone(), peer]).is_err());
        assert!(
            hosts_content(&format!("{old}1.2.3.4 {}.mct.net\n", "a".repeat(32)), &map).is_err()
        );
        assert!(!new.contains("<script>"));
    }
}
