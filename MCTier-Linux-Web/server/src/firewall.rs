//! Fixed, one-shot firewall helper. Never launch the service or EasyTier as root.
use crate::{modules::virtual_network::virtual_host, network_settings::safe_port};
use serde::{Deserialize, Serialize};
use std::{
    io::{Read, Write},
    os::unix::fs::MetadataExt,
    process::{Command, Stdio},
};

const SWITCH: &str = "--mctier-web-firewall";
const UFW: &str = "/usr/sbin/ufw";
const FIREWALL: &str = "/usr/bin/firewall-cmd";
// Debian also packages ufw in /usr/bin; these are the only permitted paths.
fn ufw_path() -> &'static str {
    if std::path::Path::new(UFW).exists() {
        UFW
    } else {
        "/usr/bin/ufw"
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Plan {
    pub backend: String,
    pub zone: String,
    pub port: u16,
    pub protocol: String,
    pub overlay_zone: String,
    pub quic_port: Option<u16>,
    pub virtual_ip: String,
    pub token: String,
    pub ephemeral_udp: Option<(u16, u16)>,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Request {
    plan: Plan,
    remove: bool,
}

impl Plan {
    pub fn validate(&self) -> Result<(), String> {
        if !matches!(self.backend.as_str(), "ufw" | "firewalld")
            || !safe_port(self.port)
            || !matches!(self.protocol.as_str(), "tcp" | "udp")
            || virtual_host(&self.virtual_ip).is_none()
            || self.token.len() != 32
            || !self
                .token
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || self
                .quic_port
                .is_some_and(|p| !safe_port(p) || p == self.port)
            || (self.backend == "firewalld"
                && [&self.zone, &self.overlay_zone].iter().any(|z| {
                    z.is_empty()
                        || z.len() > 64
                        || !z
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
                }))
            || (self.backend == "ufw" && (!self.zone.is_empty() || !self.overlay_zone.is_empty()))
        {
            return Err("防火墙计划无效".into());
        }
        if let Some((a, b)) = self.ephemeral_udp {
            if a < 32768 || a > b || b == 65535 || b - a > 32767 {
                return Err("动态 UDP 范围无效".into());
            }
        }
        Ok(())
    }
    fn items(&self) -> Vec<(&'static str, String, String)> {
        let mut items = vec![("listener", self.port.to_string(), self.protocol.clone())];
        if let Some(p) = self.quic_port {
            items.push(("quic", p.to_string(), "udp".into()));
        }
        if let Some((a, b)) = self.ephemeral_udp {
            items.push(("holepunch", format!("{a}:{b}"), "udp".into()));
        }
        items
    }
    pub fn commands(&self, remove: bool) -> Vec<Vec<String>> {
        if self.backend == "ufw" {
            let mut rules = Vec::new();
            for (label, port, proto) in self.items() {
                rules.push(vec![
                    "allow".into(),
                    "in".into(),
                    "proto".into(),
                    proto,
                    "from".into(),
                    "any".into(),
                    "to".into(),
                    "any".into(),
                    "port".into(),
                    port,
                    "comment".into(),
                    format!("MCTierWeb-{}-{label}", self.token),
                ]);
            }
            rules.push(vec![
                "allow".into(),
                "in".into(),
                "on".into(),
                "MCTier_Net".into(),
                "from".into(),
                "10.126.126.0/24".into(),
                "to".into(),
                self.virtual_ip.clone(),
                "comment".into(),
                format!("MCTierWeb-{}-overlay", self.token),
            ]);
            if remove {
                for rule in &mut rules {
                    rule.splice(0..0, ["--force".into(), "delete".into()]);
                }
            }
            rules
        } else {
            // Unique priorities identify this app's rules without deleting existing user rules.
            let priority =
                -((10000 + u16::from_str_radix(&self.token[..4], 16).unwrap_or(0) % 19000) as i32);
            let mut rules = Vec::new();
            for (_, port, proto) in self.items() {
                for family in ["ipv4", "ipv6"] {
                    rules.push((self.zone.clone(),format!("rule family=\"{family}\" priority=\"{priority}\" port port=\"{}\" protocol=\"{proto}\" accept",port.replace(':',"-"))));
                }
            }
            rules.push((self.overlay_zone.clone(),format!("rule family=\"ipv4\" priority=\"{priority}\" source address=\"10.126.126.0/24\" destination address=\"{}\" accept",self.virtual_ip)));
            rules
                .into_iter()
                .map(|(zone, rule)| {
                    let mut args = vec![
                        format!("--zone={zone}"),
                        format!(
                            "--{}-rich-rule={rule}",
                            if remove { "remove" } else { "add" }
                        ),
                    ];
                    if !remove {
                        args.push("--timeout=3600".into());
                    }
                    args
                })
                .collect()
        }
    }
    pub fn descriptions(&self) -> Vec<String> {
        let mut lines = self
            .items()
            .into_iter()
            .map(|(label, p, proto)| {
                format!(
                    "{} {p}/{proto}（{}）",
                    if label == "holepunch" {
                        "动态端口范围"
                    } else {
                        "入站端口"
                    },
                    if label == "listener" {
                        "本次 EasyTier 监听"
                    } else if label == "quic" {
                        "QUIC 代理"
                    } else {
                        "可选 UDP 打洞；同时影响范围内其它程序"
                    }
                )
            })
            .collect::<Vec<_>>();
        lines.push(format!(
            "允许虚拟网段 10.126.126.0/24 → {} 的游戏/聊天/文件入站；ufw 限定 MCTier_Net 接口",
            self.virtual_ip
        ));
        lines.push(if self.backend=="ufw" {"ufw 规则持久保存；退出后可手动撤销本次规则".into()}else{format!("firewalld：物理入站区域 {}，虚拟接口区域 {}；运行时规则 1 小时后失效，不写永久配置",self.zone,self.overlay_zone)});
        lines
    }
}
pub fn ephemeral_range() -> Option<(u16, u16)> {
    let s = std::fs::read_to_string("/proc/sys/net/ipv4/ip_local_port_range").ok()?;
    let parts = s
        .split_whitespace()
        .map(str::parse::<u16>)
        .collect::<Result<Vec<_>, _>>()
        .ok()?;
    (parts.len() == 2 && parts[0] >= 32768 && parts[0] <= parts[1] && parts[1] < 65535)
        .then(|| (parts[0], parts[1]))
}
fn trusted(path: &str) -> Result<(), String> {
    let m = std::fs::metadata(path).map_err(|_| format!("缺少 {path}"))?;
    if !m.is_file() || m.uid() != 0 || m.mode() & 0o022 != 0 {
        return Err("拒绝运行非系统所有或可被普通用户改写的防火墙工具".into());
    }
    Ok(())
}
fn command(path: &str, args: &[String]) -> Result<String, String> {
    trusted(path)?;
    let out = Command::new(path)
        .args(args)
        .env_clear()
        .env("PATH", "/usr/sbin:/usr/bin:/sbin:/bin")
        .env("LC_ALL", "C")
        .stdin(Stdio::null())
        .output()
        .map_err(|e| e.to_string())?;
    if !out.status.success() {
        return Err(format!(
            "系统工具返回失败：{}",
            String::from_utf8_lossy(&out.stderr)
                .chars()
                .take(600)
                .collect::<String>()
        ));
    }
    Ok(String::from_utf8_lossy(&out.stdout)
        .chars()
        .take(128 * 1024)
        .collect())
}
fn ufw_marked(args: &[String], output: &str) -> bool {
    let marker = args.last().unwrap();
    output.lines().any(|line| {
        line.ends_with(&format!("comment '{marker}'"))
            || line.ends_with(&format!("comment {marker}"))
    })
}
// UFW may replace matching rules when only action/comment differs. Preserve
// administrator policies and comments before adding our marked rule.
fn ufw_existing_policy(args: &[String], output: &str) -> Option<String> {
    let comment = args.iter().position(|a| a == "comment")?;
    let expected = if args.iter().any(|a| a == "on") {
        args[1..comment].to_vec()
    } else {
        let port = args.iter().position(|a| a == "port")?;
        let proto = args.iter().position(|a| a == "proto")?;
        vec![format!("{}/{}", args[port + 1], args[proto + 1])]
    };
    for line in output.lines() {
        let tokens = line
            .split(" comment ")
            .next()
            .unwrap_or("")
            .split_whitespace()
            .collect::<Vec<_>>();
        if tokens.len() < 3
            || tokens[0] != "ufw"
            || !matches!(tokens[1], "allow" | "deny" | "reject" | "limit")
        {
            continue;
        }
        let actual = tokens[2..]
            .iter()
            .filter(|t| !matches!(**t, "log" | "log-all"))
            .map(|t| t.to_string())
            .collect::<Vec<_>>();
        if actual == expected {
            return Some(tokens[1].into());
        }
    }
    None
}
fn firewalld_query(args: &[String]) -> Result<bool, String> {
    trusted(FIREWALL)?;
    let out = Command::new(FIREWALL)
        .args(args)
        .env_clear()
        .env("PATH", "/usr/sbin:/usr/bin:/sbin:/bin")
        .env("LC_ALL", "C")
        .stdin(Stdio::null())
        .output()
        .map_err(|e| e.to_string())?;
    match (
        out.status.code(),
        String::from_utf8_lossy(&out.stdout).trim(),
    ) {
        (Some(0), "yes") => Ok(true),
        (Some(1), "no") => Ok(false),
        _ => Err("无法复核 firewalld 规则，请检查系统防火墙".into()),
    }
}
fn execute(request: &Request) -> Result<Vec<String>, String> {
    request.plan.validate()?;
    let p = &request.plan;
    if !request.remove && p.ephemeral_udp.is_some() && p.ephemeral_udp != ephemeral_range() {
        return Err("系统动态端口范围已改变，请重新预览".into());
    }
    let tool = if p.backend == "ufw" {
        ufw_path()
    } else {
        FIREWALL
    };
    if p.backend == "ufw" {
        let status = command(tool, &["status".into()])?;
        if !status.lines().any(|l| l == "Status: active") {
            return Err("ufw 未启用；本功能不会启用、防停或重置防火墙".into());
        }
    } else {
        command(tool, &["--state".into()])?;
        let zones = command(tool, &["--get-zones".into()])?;
        if [&p.zone, &p.overlay_zone]
            .iter()
            .any(|zone| !zones.split_whitespace().any(|z| z == zone.as_str()))
        {
            return Err("所选 firewalld 区域不存在".into());
        }
        if !request.remove {
            let current = command(tool, &["--get-zone-of-interface=MCTier_Net".into()])?;
            let current = if current.trim() == "no zone" {
                command(tool, &["--get-default-zone".into()])?
            } else {
                current
            };
            if current.trim() != p.overlay_zone {
                return Err("虚拟接口区域已改变，请重新预览".into());
            }
        }
    }
    let mut report = vec![];
    for args in p.commands(request.remove) {
        if p.backend == "ufw" {
            let before = command(tool, &["show".into(), "added".into()])?;
            if request.remove && !ufw_marked(&args, &before) {
                report.push("本次标记的规则已不存在；未删除其它规则".into());
                continue;
            }
            if !request.remove && ufw_marked(&args, &before) {
                report.push("本次规则已存在".into());
                continue;
            }
            if !request.remove {
                if let Some(policy) = ufw_existing_policy(&args, &before) {
                    if policy != "allow" {
                        return Err(format!("已有管理员 {policy} 规则与本项相同，拒绝覆盖。已完成 {} 项；请手动审查规则",report.len()));
                    }
                    report
                        .push("已有管理员放行规则，保留原有注释和所有权；本应用不会撤销它".into());
                    continue;
                }
            }
            if let Err(e) = command(tool, &args) {
                return Err(format!(
                    "已有 {} 项完成；{e}。可在修复面板撤销本次已添加规则",
                    report.len()
                ));
            }
            let after = command(tool, &["show".into(), "added".into()])?;
            let marked = ufw_marked(&args, &after);
            if marked == request.remove {
                if !request.remove {
                    report.push(
                        "匹配的规则可能由管理员预先添加；没有本次标记，不会将它用于撤销".into(),
                    );
                } else {
                    return Err("撤销后的规则复核失败".into());
                }
            } else {
                report.push(if request.remove {
                    "已撤销并复核".into()
                } else {
                    "已添加并复核".into()
                });
            }
        } else {
            let query = args
                .iter()
                .filter(|a| !a.starts_with("--timeout"))
                .map(|a| {
                    a.replace("--add-rich-rule=", "--query-rich-rule=")
                        .replace("--remove-rich-rule=", "--query-rich-rule=")
                })
                .collect::<Vec<_>>();
            let before = firewalld_query(&query)?;
            if before == !request.remove {
                report.push(if before {
                    "本次规则已存在".into()
                } else {
                    "本次规则已不存在".into()
                });
                continue;
            }
            if let Err(e) = command(tool, &args) {
                return Err(format!(
                    "已有 {} 项完成；{e}。可撤销本次规则，或等待 1 小时失效",
                    report.len()
                ));
            }
            let after = firewalld_query(&query)?;
            if after != !request.remove {
                return Err("firewalld 规则复核失败，请查看系统防火墙状态".into());
            }
            report.push(if request.remove {
                "已撤销并复核".into()
            } else {
                "已添加并复核".into()
            });
        }
    }
    Ok(report)
}
pub async fn apply(
    helper: crate::privileged::Helper,
    plan: Plan,
    remove: bool,
    cancel: &mut tokio::sync::watch::Receiver<u64>,
) -> Result<Vec<String>, String> {
    plan.validate()?;
    let out = helper
        .execute(
            serde_json::to_value(Request { plan, remove }).map_err(|e| e.to_string())?,
            cancel,
        )
        .await?;
    serde_json::from_slice::<Result<Vec<String>, String>>(&out)
        .map_err(|_| "授权结果无效".to_string())?
}
pub async fn authorize(
    cancel: &mut tokio::sync::watch::Receiver<u64>,
) -> Result<crate::privileged::Helper, String> {
    crate::privileged::Helper::start(SWITCH, cancel).await
}
pub fn run_if_requested() {
    let args: Vec<_> = std::env::args_os().collect();
    if args.get(1).is_none_or(|a| a != SWITCH) {
        return;
    }
    let result = (|| {
        if args.len() != 2 || unsafe { libc::geteuid() } != 0 {
            return Err("仅限一次性授权助手".into());
        }
        crate::privileged::ready();
        let mut data = vec![];
        std::io::stdin()
            .take(4097)
            .read_to_end(&mut data)
            .map_err(|e| e.to_string())?;
        if data.len() > 4096 {
            return Err("请求过大".into());
        }
        let req: Request = serde_json::from_slice(&data).map_err(|_| "授权请求无效")?;
        execute(&req)
    })();
    let ok = result.is_ok();
    let _ = serde_json::to_writer(std::io::stdout(), &result);
    let _ = std::io::stdout().flush();
    std::process::exit(if ok { 0 } else { 1 });
}
pub async fn detect() -> serde_json::Value {
    let has_ufw = trusted(ufw_path()).is_ok();
    let has_firewalld = trusted(FIREWALL).is_ok();
    let mut zones = Vec::new();
    let mut default_zone = String::new();
    let mut overlay_zone = String::new();
    if has_firewalld {
        for (args, default) in [(["--get-zones"], false), (["--get-default-zone"], true)] {
            if let Ok(Ok(o)) = tokio::time::timeout(
                std::time::Duration::from_secs(3),
                tokio::process::Command::new(FIREWALL)
                    .args(args)
                    .kill_on_drop(true)
                    .output(),
            )
            .await
            {
                if o.status.success() {
                    let s = String::from_utf8_lossy(&o.stdout);
                    if default {
                        default_zone = s.trim().into()
                    } else {
                        zones = s.split_whitespace().map(str::to_owned).collect()
                    }
                }
            }
        }
        if let Ok(Ok(o)) = tokio::time::timeout(
            std::time::Duration::from_secs(3),
            tokio::process::Command::new(FIREWALL)
                .arg("--get-zone-of-interface=MCTier_Net")
                .kill_on_drop(true)
                .output(),
        )
        .await
        {
            if o.status.success() {
                let s = String::from_utf8_lossy(&o.stdout);
                overlay_zone = if s.trim() == "no zone" {
                    default_zone.clone()
                } else {
                    s.trim().into()
                };
            }
        }
    }
    serde_json::json!({"ufw":has_ufw,"firewalld":has_firewalld,"zones":zones,"defaultZone":default_zone,"overlayZone":overlay_zone,"ephemeralRange":ephemeral_range()})
}
#[cfg(test)]
mod tests {
    use super::*;
    fn plan() -> Plan {
        Plan {
            backend: "ufw".into(),
            zone: String::new(),
            overlay_zone: String::new(),
            port: 31111,
            protocol: "udp".into(),
            quic_port: Some(31112),
            virtual_ip: "10.126.126.2".into(),
            token: "a".repeat(32),
            ephemeral_udp: None,
        }
    }
    #[test]
    fn plan_cannot_open_controls_or_inject_commands() {
        let p = plan();
        assert!(p.validate().is_ok());
        for port in [22, 14700, 14539, 14540] {
            let mut q = p.clone();
            q.port = port;
            assert!(q.validate().is_err());
        }
        let mut q = p.clone();
        q.virtual_ip = "127.0.0.1".into();
        assert!(q.validate().is_err());
        q = p.clone();
        q.backend = "firewalld".into();
        q.zone = "public; reboot".into();
        assert!(q.validate().is_err());
        let c = p.commands(false);
        assert!(c.last().unwrap().contains(&"MCTier_Net".into()));
        assert!(!c
            .iter()
            .flatten()
            .any(|a| a == "enable" || a == "disable" || a == "14700"));
        q.zone = "public".into();
        q.overlay_zone = "trusted".into();
        assert!(q.validate().is_ok());
        assert!(q
            .commands(false)
            .iter()
            .all(|a| a.contains(&"--timeout=3600".into()) && !a.contains(&"--permanent".into())));
        assert!(q
            .commands(false)
            .last()
            .unwrap()
            .contains(&"--zone=trusted".into()));
    }
    #[test]
    fn existing_rules_preserve_policy_and_ownership() {
        let p = plan();
        let add = p.commands(false);
        assert_eq!(
            ufw_existing_policy(&add[0], "ufw deny 31111/udp comment 'admin'"),
            Some("deny".into())
        );
        assert_eq!(
            ufw_existing_policy(&add[0], "ufw allow log 31111/udp"),
            Some("allow".into())
        );
        assert_eq!(
            ufw_existing_policy(
                add.last().unwrap(),
                "ufw allow in on MCTier_Net from 10.126.126.0/24 to 10.126.126.2 comment 'admin'"
            ),
            Some("allow".into())
        );
        assert!(ufw_existing_policy(&add[0], "ufw allow out 31111/udp").is_none());
    }
    #[test]
    fn removal_needs_exact_owned_marker() {
        let p = plan();
        let a = p.commands(true);
        assert!(!ufw_marked(&a[0], "ufw allow 31111/udp"));
        assert!(ufw_marked(
            &a[0],
            &format!(
                "ufw allow in proto udp from any to any port 31111 comment 'MCTierWeb-{}-listener'",
                p.token
            )
        ));
    }
}
