//! Explicit opt-in startup network profile; no headless signaling or media.
use crate::{modules::{app_paths, secret_store}, runtime::{App, LobbyInput, validate_input}};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{io::{Read, Write}, os::unix::fs::{OpenOptionsExt, MetadataExt}, sync::Arc};

#[derive(Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config { pub enabled: bool, pub lobby: Option<LobbyInput> }
fn path() -> Result<std::path::PathBuf, String> {
    Ok(app_paths::data_root().map_err(|e| e.to_string())?.join("linux-web/auto-join.json"))
}
fn read() -> Result<Config, String> {
    let file = match std::fs::OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW).open(path()?) {
        Ok(f) => f, Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Config::default()),
        Err(_) => return Err("自动加入配置不能安全读取".into()),
    };
    let meta = file.metadata().map_err(|_| "自动加入配置无法检查")?;
    if !meta.is_file() || meta.uid() != unsafe { libc::geteuid() } || meta.mode() & 0o077 != 0 { return Err("自动加入配置须为本用户私有文件（0600）".into()); }
    let mut data = Vec::new(); file.take(65537).read_to_end(&mut data).map_err(|_| "自动加入配置读取失败")?;
    if data.len() > 65536 { return Err("自动加入配置过大".into()); }
    let c: Config = serde_json::from_slice(&data).map_err(|_| "自动加入配置格式无效")?;
    if let Some(lobby) = &c.lobby {
        if !lobby.password.is_empty() && !lobby.password.starts_with(secret_store::LOCAL_PREFIX) { return Err("自动加入密码必须为本机加密数据".into()); }
    }
    Ok(c)
}
pub fn get() -> Result<Value, String> {
    let c = read()?;
    Ok(json!({"enabled":c.enabled,"name":c.lobby.as_ref().map(|l| &l.name),"playerName":c.lobby.as_ref().map(|l| &l.player_name),"lobby":c.lobby}))
}
pub fn save(mut c: Config) -> Result<Value, String> {
    if c.enabled {
        let l = c.lobby.as_mut().ok_or("请提供启动目标大厅")?;
        l.password = secret_store::resolve(&l.password)?;
        l.address_attempt = 0;
        validate_input(l)?;
        l.password = secret_store::protect_lobby_password(l.password.clone())?;
    } else { c.lobby = None; }
    let path = path()?; let parent = path.parent().unwrap();
    std::fs::create_dir_all(parent).map_err(|_| "不能创建自动加入配置目录")?;
    let m = std::fs::symlink_metadata(parent).map_err(|_| "不能检查配置目录")?;
    if !m.is_dir() || m.uid() != unsafe { libc::geteuid() } || m.mode() & 0o022 != 0 { return Err("自动加入目录须属于当前用户且不得为链接".into()); }
    if let Ok(m) = std::fs::symlink_metadata(&path) { if !m.is_file() || m.uid() != unsafe { libc::geteuid() } { return Err("拒绝覆盖配置链接或其它用户文件".into()); } }
    let data = serde_json::to_vec(&c).map_err(|_| "配置编码失败")?;
    if data.len() > 65536 { return Err("自动加入配置过大".into()); }
    let tmp = parent.join(format!(".auto-join-{}.tmp", uuid::Uuid::new_v4().simple()));
    let result: Result<(), String> = (|| {
        let mut file = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp).map_err(|_| "配置临时文件创建失败")?;
        file.write_all(&data).and_then(|_| file.sync_all()).map_err(|_| "配置保存失败")?;
        std::fs::rename(&tmp, &path).map_err(|_| "配置替换失败")?;
        Ok(())
    })();
    let _ = std::fs::remove_file(&tmp);
    result?; get()
}
pub async fn startup(app: Arc<App>) {
    let result = async {
        let c = read()?;
        if !c.enabled { return Ok(false); }
        *app.auto_startup.lock().await = json!({"state":"starting"});
        let mut input = c.lobby.ok_or("自动加入缺少目标")?;
        input.password = secret_store::resolve(&input.password)?;
        let ticket = app.claim_ticket(&"0".repeat(32)).await?;
        app.start(input, &ticket).await?;
        let mut rt = app.runtime.lock().await;
        rt.owner = None;
        rt.auto_network = true;
        Ok::<bool, String>(true)
    }.await;
    match result {
        Ok(enabled) => *app.auto_startup.lock().await = json!({"state":if enabled {"ready"} else {"disabled"}}),
        Err(error) => { let mut rt = app.runtime.lock().await; if rt.owner.as_deref() == Some(&"0".repeat(32)) { app.stop_runtime(&mut rt).await; } drop(rt); *app.auto_startup.lock().await = json!({"state":"failed","error":error}); }
    }
}
