//! Fixed backend stop/restore with a private, persistent privilege-owned receipt.
use crate::firewall::{self, Plan};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    os::{
        fd::AsRawFd,
        unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt},
    },
    path::Path,
};
const ROOT: &str = "/var/lib/mctier-linux-web/firewall-pauses";
const SYSTEMCTL: &str = "/usr/bin/systemctl";
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Receipt {
    backend: String,
    active: bool,
}
trait Backend {
    fn active(&self, backend: &str) -> Result<bool, String>;
    fn set(&self, backend: &str, active: bool) -> Result<(), String>;
}
struct System;
impl Backend for System {
    fn active(&self, backend: &str) -> Result<bool, String> {
        if backend == "ufw" {
            let result = firewall::command(firewall::ufw_path(), &["status".into()])?;
            if result.lines().any(|l| l == "Status: active") {
                Ok(true)
            } else if result.lines().any(|l| l == "Status: inactive") {
                Ok(false)
            } else {
                Err("无法确认 ufw 原状态".into())
            }
        } else {
            let result = firewall::command(
                SYSTEMCTL,
                &[
                    "show".into(),
                    "--property=LoadState".into(),
                    "--property=ActiveState".into(),
                    "firewalld.service".into(),
                ],
            )?;
            if !result.lines().any(|l| l == "LoadState=loaded") {
                return Err("firewalld 服务未安装".into());
            }
            if result.lines().any(|l| l == "ActiveState=active") {
                Ok(true)
            } else if result
                .lines()
                .any(|l| l == "ActiveState=inactive" || l == "ActiveState=failed")
            {
                Ok(false)
            } else {
                Err("firewalld 正在变更状态，请稍后重试".into())
            }
        }
    }
    fn set(&self, backend: &str, active: bool) -> Result<(), String> {
        let (tool, args) = if backend == "ufw" {
            (
                firewall::ufw_path(),
                vec![
                    "--force".into(),
                    if active { "enable" } else { "disable" }.into(),
                ],
            )
        } else {
            (
                SYSTEMCTL,
                vec![
                    if active { "start" } else { "stop" }.into(),
                    "firewalld.service".into(),
                ],
            )
        };
        firewall::command(tool, &args)?;
        if self.active(backend)? != active {
            return Err("防火墙运行状态复核失败，保留恢复凭证".into());
        }
        Ok(())
    }
}
fn private_dir(path: &Path) -> Result<(), String> {
    match fs::DirBuilder::new().mode(0o700).create(path) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(e) => return Err(e.to_string()),
    }
    let m = fs::symlink_metadata(path).map_err(|e| e.to_string())?;
    if !m.is_dir() || m.uid() != unsafe { libc::geteuid() } || m.mode() & 0o022 != 0 {
        return Err("暂停凭证目录不安全".into());
    }
    Ok(())
}
fn read_receipt(path: &Path) -> Result<Option<Receipt>, String> {
    let file = match OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
    {
        Ok(f) => f,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.to_string()),
    };
    let m = file.metadata().map_err(|e| e.to_string())?;
    if !m.is_file()
        || m.uid() != unsafe { libc::geteuid() }
        || m.mode() & 0o077 != 0
        || m.len() > 1024
    {
        return Err("暂停凭证不安全".into());
    }
    let mut data = Vec::new();
    file.take(1025)
        .read_to_end(&mut data)
        .map_err(|e| e.to_string())?;
    serde_json::from_slice(&data)
        .map(Some)
        .map_err(|_| "暂停凭证无效".into())
}
fn apply_at(
    root: &Path,
    plan: &Plan,
    remove: bool,
    backend: &impl Backend,
) -> Result<Vec<String>, String> {
    plan.validate()?;
    if !plan.pause {
        return Err("不是暂停计划".into());
    }
    private_dir(root.parent().ok_or("路径无效")?)?;
    private_dir(root)?;
    let lock = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(root.join(".lock"))
        .map_err(|e| e.to_string())?;
    let m = lock.metadata().map_err(|e| e.to_string())?;
    if !m.is_file() || m.uid() != unsafe { libc::geteuid() } || m.mode() & 0o077 != 0 {
        return Err("暂停锁不安全".into());
    }
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err("另一项防火墙暂停操作进行中".into());
    }
    let path = root.join(format!("{}.json", plan.token));
    let prior = read_receipt(&path)?;
    if remove {
        if let Some(prior) = prior {
            if prior.backend != plan.backend {
                return Err("凭证后端不匹配".into());
            }
            if prior.active && !backend.active(&plan.backend)? {
                backend.set(&plan.backend, true)?;
            }
            fs::remove_file(path).map_err(|e| e.to_string())?;
            fs::File::open(root)
                .and_then(|f| f.sync_all())
                .map_err(|e| e.to_string())?;
            return Ok(vec![if prior.active {
                "已恢复并复核防火墙运行状态"
            } else {
                "原防火墙未运行；保持原状态，未擅自启用"
            }
            .into()]);
        }
        return Ok(vec!["无本次暂停凭证；未更改防火墙状态".into()]);
    }
    let entries = fs::read_dir(root).map_err(|e| e.to_string())?;
    for entry in entries {
        let entry = entry.map_err(|e| e.to_string())?;
        if entry.path() != path && entry.path().extension().is_some_and(|x| x == "json") {
            if read_receipt(&entry.path())?.is_some_and(|r| r.backend == plan.backend) {
                return Err("已有该后端的暂停凭证，请先恢复".into());
            }
        }
    }
    let prior = if let Some(prior) = prior {
        if prior.backend != plan.backend {
            return Err("凭证后端不匹配".into());
        }
        prior
    } else {
        let prior = Receipt {
            backend: plan.backend.clone(),
            active: backend.active(&plan.backend)?,
        };
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&path)
            .map_err(|e| e.to_string())?;
        file.write_all(&serde_json::to_vec(&prior).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
        file.sync_all().map_err(|e| e.to_string())?;
        fs::File::open(root)
            .and_then(|f| f.sync_all())
            .map_err(|e| e.to_string())?;
        prior
    };
    if backend.active(&plan.backend)? {
        backend.set(&plan.backend, false)?;
    }
    Ok(vec![if prior.active {
        "防火墙已暂停并复核；原状态凭证已保存，请在退出时或修复面板恢复"
    } else {
        "防火墙原本未运行；保留状态，恢复不会擅自启用"
    }
    .into()])
}
pub(crate) fn backend_active(backend: &str) -> Result<bool, String> {
    System.active(backend)
}
pub fn execute(plan: &Plan, remove: bool) -> Result<Vec<String>, String> {
    apply_at(Path::new(ROOT), plan, remove, &System)
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    struct Fake(Cell<bool>);
    impl Backend for Fake {
        fn active(&self, _: &str) -> Result<bool, String> {
            Ok(self.0.get())
        }
        fn set(&self, _: &str, active: bool) -> Result<(), String> {
            self.0.set(active);
            Ok(())
        }
    }
    fn plan() -> Plan {
        Plan {
            backend: "ufw".into(),
            zone: String::new(),
            port: 0,
            protocol: String::new(),
            tcp_listener: false,
            overlay_zone: String::new(),
            quic_port: None,
            virtual_ip: String::new(),
            token: "a".repeat(32),
            ephemeral_udp: None,
            ephemeral_tcp: None,
            pause: true,
            allow_outgoing: false,
        }
    }
    #[test]
    fn restore_respects_original_state_and_survives_repeated_pause() {
        for original in [false, true] {
            let dir = tempfile::tempdir().unwrap();
            let root = dir.path().join("journal");
            let backend = Fake(Cell::new(original));
            let plan = plan();
            apply_at(&root, &plan, false, &backend).unwrap();
            assert!(!backend.0.get());
            apply_at(&root, &plan, false, &backend).unwrap();
            apply_at(&root, &plan, true, &backend).unwrap();
            assert_eq!(backend.0.get(), original);
            apply_at(&root, &plan, true, &backend).unwrap();
            assert_eq!(backend.0.get(), original);
        }
    }
    #[test]
    fn partial_pause_failure_keeps_the_original_state_for_restore() {
        struct Partial {
            active: Cell<bool>,
            fail: Cell<bool>,
        }
        impl Backend for Partial {
            fn active(&self, _: &str) -> Result<bool, String> {
                Ok(self.active.get())
            }
            fn set(&self, _: &str, active: bool) -> Result<(), String> {
                self.active.set(active);
                if self.fail.replace(false) {
                    Err("模拟变更后的复核失败".into())
                } else {
                    Ok(())
                }
            }
        }
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("journal");
        let backend = Partial {
            active: Cell::new(true),
            fail: Cell::new(true),
        };
        let p = plan();
        assert!(apply_at(&root, &p, false, &backend).is_err());
        assert!(!backend.active.get());
        assert!(root.join(format!("{}.json", p.token)).exists());
        apply_at(&root, &p, true, &backend).unwrap();
        assert!(backend.active.get());
    }
    #[test]
    fn second_pause_and_symlink_receipt_are_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("journal");
        let backend = Fake(Cell::new(true));
        let mut p = plan();
        apply_at(&root, &p, false, &backend).unwrap();
        p.token = "b".repeat(32);
        assert!(apply_at(&root, &p, false, &backend).is_err());
        let target = dir.path().join("other");
        fs::write(&target, b"{}").unwrap();
        std::os::unix::fs::symlink(target, root.join(format!("{}.json", p.token))).unwrap();
        assert!(apply_at(&root, &p, true, &backend).is_err());
    }
}
